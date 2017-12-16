package com.dun.nkcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class ByteBufKCP {

    private final Logger LOGGER = LoggerFactory.getLogger(ByteBufKCP.class);
    /**
     * kcp 单数据包最小长度
     */
    public final int IKCP_OVERHEAD = 24;
    /**
     * cmd: push data
     */
    public final int IKCP_CMD_PUSH = 81;

    /**
     * cmd: ack
     */
    public final int IKCP_CMD_ACK = 82;

    /**
     * cmd: window probe (ask)
     */
    public final int IKCP_CMD_WASK = 83;

    /**
     * cmd: window size (tell)
     */
    public final int IKCP_CMD_WINS = 84;


    public final int IKCP_WND_RCV = 32;

    public final int IKCP_RTO_DEF = 200;

    /**
     * normal min rto
     */
    public final int IKCP_RTO_MIN = 100;

    public final int IKCP_RTO_MAX = 60000;

    /**
     * need to send IKCP_CMD_WINS
     */
    public final int IKCP_ASK_TELL = 2;

    public final int IKCP_MTU_DEF = 1400;

    public final int IKCP_THRESH_INIT = 2;

    /**
     *  第一个未确认的包
     */
    long sndUna = 0;
    /**
     * 会话ID
     */
    long conv = 0;
    /**
     * 待发送包的序号
     */
    long sndNxt = 0;
    /**
     *
     */
    long current = 0;
    /**
     * ack接收rtt静态值
     */
    long rxSrtt = 0;
    /**
     * 	ack接收rtt浮动值
     */
    long rxRttval = 0;
    /**
     * 待接收消息序号
     */
    long rcvNxt = 0;
    /**
     * 探查变量，IKCP_ASK_TELL表示告知远端窗口大小。IKCP_ASK_SEND表示请求远端告知窗口大小
     */
    long probe = 0;
    /**
     * 拥塞窗口大小
     */
    long cwnd = 0;
    /**
     * 可发送的最大数据量
     */
    long incr = 0;
    /**
     * 拥塞窗口阈值
     */
    long ssthresh = IKCP_THRESH_INIT;
    /**
     * 最大传输单元
     */
    long mtu = IKCP_MTU_DEF;
    /**
     * 最大数据分片大小
     */
    long mss = this.mtu - IKCP_OVERHEAD;
    /**
     * 接收窗口大小
     */
    long rcvWnd = IKCP_WND_RCV;
    /**
     *  最小复原时间
     */
    long rxMinrto = IKCP_RTO_MIN;
    /**
     * 由ack接收延迟计算出来的复原时间
     */
    long rxRto = IKCP_RTO_DEF;
    /**
     * 远端接收窗口大小
     */
    long rmtWnd = IKCP_WND_RCV;
    /**
     * 发送消息的缓存
     */
    List<Segment> nsndBuf = new ArrayList<>(128);
    /**
     * 待发送的ack列表
     */
    List<Long> ackList = new ArrayList<>(128);
    /**
     * 接收消息的缓存
     */
    List<Segment> nrcvBuf = new ArrayList<>(128);
    /**
     * 	接收消息的队列
     */
    List<Segment> nrcvQue = new ArrayList<>(128);


    private ByteBufAllocator bufAllocator;

    private ProtocolUnitOutputCallback outputCallback;

    private ProtocolUnitRecvCallback recvCallback;


    public ByteBufKCP(ByteBufAllocator byteBufAllocator,int conv){
        this.bufAllocator = byteBufAllocator;
        this.conv = conv;
    }

    public void setRecvCallback(ProtocolUnitRecvCallback recvCallback) {
        this.recvCallback = recvCallback;
    }

    public void setOutputCallback(ProtocolUnitOutputCallback outputCallback) {
        this.outputCallback = outputCallback;
    }

    private class Segment {

        protected long conv = 0;
        protected long cmd = 0;
        protected long frg = 0;
        protected long wnd = 0;
        protected long ts = 0;
        protected long sn = 0;
        protected long una = 0;
        protected long resendts = 0;
        protected long rto = 0;
        protected long fastack = 0;
        protected long xmit = 0;
        protected ByteBuf data;

        protected Segment(ByteBuf data) {
            this.data = data;
        }

        /**
         * encode a segment into buffer
         * @param ptr
         * @param offset
         * @return
         */
        protected int encode(ByteBuf ptr, int offset) {
            int offset_ = offset;
            iKcpEncode32u(ptr, offset, conv);
            offset += 4;
            ikcp_encode8u(ptr, offset, (byte) cmd);
            offset += 1;
            ikcp_encode8u(ptr, offset, (byte) frg);
            offset += 1;
            iKcpEncode16u(ptr, offset, (int) wnd);
            offset += 2;
            iKcpEncode32u(ptr, offset, ts);
            offset += 4;
            iKcpEncode32u(ptr, offset, sn);
            offset += 4;
            iKcpEncode32u(ptr, offset, una);
            offset += 4;
            iKcpEncode32u(ptr, offset, (long) data.readableBytes());
            offset += 4;
            return offset - offset_;
        }
    }

    /**
     * encode 32 bits unsigned int (msb)
     * @param data
     * @param offset
     * @param l
     */
    public static void iKcpEncode32u(ByteBuf data, int offset, long l) {
        data.setByte(offset,(byte) (l >> 24));
        data.setByte(offset + 1,(byte) (l >> 16));
        data.setByte(offset + 2,(byte) (l >> 8));
        data.setByte(offset + 3,(byte) (l >> 0));
    }

    /**
     * encode 16 bits unsigned int (msb)
     * @param data
     * @param offset
     * @param w
     */
    public static void iKcpEncode16u(ByteBuf data, int offset, int w) {
        data.setByte(offset,(byte) (w >> 8));
        data.setByte(offset + 1,(byte) (w >> 0));
    }

    /**
     * encode 8 bits unsigned int
     * @param data
     * @param offset
     * @param c
     */
    public static void ikcp_encode8u(ByteBuf data, int offset, byte c) {
        data.setByte(offset,c);
    }



    /**
     * decode 32 bits unsigned int (msb)
     * @param data
     * @return
     */
    public static long iKcpDecode32u(ByteBuf data) {
        long ret = (data.readByte() & 0xFFL) << 24
                | (data.readByte() & 0xFFL) << 16
                | (data.readByte() & 0xFFL) << 8
                | data.readByte() & 0xFFL;
        return ret;
    }

    /**
     * decode 8 bits unsigned int
     * @param data
     * @return
     */
    public static byte iKcpDecode8u(ByteBuf data) {
        return data.readByte();
    }

    /**
     * decode 16 bits unsigned int (msb)
     * @param data
     * @return
     */
    public static int iKcpDecode16u(ByteBuf data) {
        int ret = (data.readByte() & 0xFF) << 8
                | (data.readByte() & 0xFF);
        return ret;
    }

    static int iTimeDiff(long later, long earlier) {
        return ((int) (later - earlier));
    }

    public static void slice(List<Segment> list, int start, int stop) {
        int size = list.size();
        for (int i = 0; i < size; ++i) {
            if (i < stop - start) {
                list.set(i, list.get(i + start));
            } else {
                list.remove(stop - start);
            }
        }
    }

    /**
     * 计算本地真实snd_una
     */
    void shrinkBuf() {
        if (nsndBuf.size() > 0) {
            sndUna = nsndBuf.get(0).sn;
        } else {
            sndUna = sndNxt;
        }
    }


    /**
     * 通过对端传回的una将已经确认发送成功包从发送缓存中移除
     * @param una
     */
    private void parseUna(long una) {
        int count = 0;
        for (Segment seg : nsndBuf) {
            if (iTimeDiff(una, seg.sn) > 0) {
                count++;
            } else {
                break;
            }
        }

        if (0 < count) {
            slice(nsndBuf, count, nsndBuf.size());
        }
    }

    static long iMax(long a, long b) {
        return a >= b ? a : b;
    }

    static long iMin(long a, long b) {
        return a <= b ? a : b;
    }

    static long iBound(long lower, long middle, long upper) {
        return iMin(iMax(lower, middle), upper);
    }

    /**
     * parse ack
     * @param rtt
     */
    void updateAck(int rtt) {
        if (0 == rxSrtt) {
            rxSrtt = rtt;
            rxRttval = rtt / 2;
        } else {
            int delta = (int) (rtt - rxSrtt);
            if (0 > delta) {
                delta = -delta;
            }

            rxRttval = (3 * rxRttval + delta) / 4;
            rxSrtt = (7 * rxSrtt + rtt) / 8;
            if (rxSrtt < 1) {
                rxSrtt = 1;
            }
        }

        int rto = (int) (rxSrtt + iMax(1, 4 * rxRttval));
        rxRto = iBound(rxMinrto, rto, IKCP_RTO_MAX);
    }

    /**
     * 对端返回的ack, 确认发送成功时，对应包从发送缓存中移除
     * @param sn
     */
    void parseAck(long sn) {
        if (iTimeDiff(sn, sndUna) < 0 || iTimeDiff(sn, sndNxt) >= 0) {
            return;
        }

        int index = 0;
        for (Segment seg : nsndBuf) {
            if (iTimeDiff(sn, seg.sn) < 0) {
                break;
            }
            // 原版ikcp_parse_fastack&ikcp_parse_ack逻辑重复
            seg.fastack++;

            if (sn == seg.sn) {
                nsndBuf.remove(index);
                break;
            }
            index++;
        }
    }

    /**
     * ack append
     * 收数据包后需要给对端回ack，flush时发送出去
     * @param sn
     * @param ts
     */
    void ackPush(long sn, long ts) {
        // c原版实现中按*2扩大容量
        ackList.add(sn);
        ackList.add(ts);
    }

    /**
     * 用户数据包解析
     * @param newSeg
     */
    void parseData(Segment newSeg) {
        long sn = newSeg.sn;
        boolean repeat = false;

        if (iTimeDiff(sn, rcvNxt + rcvWnd) >= 0 || iTimeDiff(sn, rcvNxt) < 0) {
            return;
        }
        //取缓存最后一个
        int n = nrcvBuf.size() - 1;
        int afterIdx = -1;

        // 判断是否是重复包,并且计算插入位置(这个是自排序的)
        for (int i = n; i >= 0; i--) {
            Segment seg = nrcvBuf.get(i);
            if (seg.sn == sn) {
                repeat = true;
                break;
            }

            if (iTimeDiff(sn, seg.sn) > 0) {
                afterIdx = i;
                break;
            }
        }

        // 如果不是重复包，则插入
        if (!repeat) {
            if (afterIdx == -1) {
                nrcvBuf.add(0, newSeg);
            } else {
                nrcvBuf.add(afterIdx + 1, newSeg);
            }
        }

        // 将连续包加入到接收队列
        int count = 0;
        for (Segment seg : nrcvBuf) {
            if (seg.sn == rcvNxt && nrcvQue.size() < rcvWnd) {
                nrcvQue.add(seg);
                rcvNxt++;
                count++;
            } else {
                break;
            }
        }
        // 从接收缓存中移除
        if (0 < count) {
            slice(nrcvBuf, count, nrcvBuf.size());
        }

        checkAbledRecvAndCallback();
    }

    private void checkAbledRecvAndCallback() {
        if (0 == nrcvQue.size()) {
            LOGGER.debug("接受队列里面没有数据:result -1");
        }

        int peekSize = peekSize();
        if (0 > peekSize) {
            LOGGER.debug("接受队列里面无足够的数据:result -2");
        }

        boolean recover = false;
        if (nrcvQue.size() >= rcvWnd) {
            recover = true;
        }

        // merge fragment.
        CompositeByteBuf byteBufs = bufAllocator.compositeBuffer();
        for (Segment seg : nrcvQue) {
            byteBufs.addComponent(seg.data);
            if (0 == seg.frg) {
                break;
            }
        }
        int count = byteBufs.numComponents();
        if (0 < count) {
            slice(nrcvQue, count, nrcvQue.size());
        }
        // fast recover
        if (nrcvQue.size() < rcvWnd && recover) {
            // ready to send back IKCP_CMD_WINS in ikcp_flush
            // tell remote my window size
            probe |= IKCP_ASK_TELL;
        }
        //调用recv回调函数
        recvCallback.recv(byteBufs);
    }


    //---------------------------------------------------------------------
    // user/upper level send, returns below zero for error
    //---------------------------------------------------------------------
    // 上层要发送的数据丢给发送队列，发送队列会根据mtu大小分片
    public int Send(ByteBuf buffer) {

        if (0 == buffer.readableBytes()) {
            return -1;
        }

        int count;

        // 根据mss大小分片
        if (buffer.readableBytes() < mss) {
            count = 1;
        } else {
            count = (int) (buffer.readableBytes() + mss - 1) / (int) mss;
        }

        if (255 < count) {
            return -2;
        }

        if (0 == count) {
            count = 1;
        }

        int offset = 0;

        // 分片后加入到发送队列
        int length = buffer.readableBytes();
        for (int i = 0; i < count; i++) {
            int size = (int) (length > mss ? mss : length);
            buffer.readBytes(size);
            Segment seg = new Segment(size);
            System.arraycopy(buffer, offset, seg.data, 0, size);
            offset += size;
            seg.frg = count - i - 1;
            nsnd_que.add(seg);
            length -= size;
        }
        return 0;
    }

    /**
     * 底层收包后调用，再由上层通过Recv获得处理后的数据
     * @param data
     * @return
     */
    public int input(ByteBuf data){
        long sUna = sndUna;
        //判断接受到的数据长度是否 小于kcp最小数据包长度,如果不是,返回0
        if (data.readableBytes() < IKCP_OVERHEAD) {
            return 0;
        }
        while (true) {
            long ts, sn, length, una, convTemp;
            int wnd;
            byte cmd, frg;

            if (data.readableBytes() < IKCP_OVERHEAD) {
                break;
            }
            //解码头四个字节(解码出conv 会话标识)
            convTemp = iKcpDecode32u(data);
            //如果会话标识不相等，则不是这个kcp链接的数据包 返回-1
            if (conv != convTemp) {
                return -1;
            }
            //解码出一个字节
            cmd = iKcpDecode8u(data);
            //解码出一个字节
            frg = iKcpDecode8u(data);
            //解码出两个字节
            wnd = iKcpDecode16u(data);
            //解码出四个字节
            ts = iKcpDecode32u(data);
            //解码出四个字节
            sn = iKcpDecode32u(data);
            //解码出四个字节
            una = iKcpDecode32u(data);
            //解码出四个字节
            length = iKcpDecode32u(data);
            //算上前面的会话标识总共解码出了24个字节的数据(解码出了全部头数据)

            //在解码出了全部头数据的情况下再次检查长度
            if (data.readableBytes() < length) {
                return -2;
            }
            //cmd参数不是这四个中的一个 就是错误的命令 返回-3
            if (cmd != IKCP_CMD_PUSH && cmd != IKCP_CMD_ACK && cmd != IKCP_CMD_WASK && cmd != IKCP_CMD_WINS) {
                return -3;
            }

            rmtWnd = (long) wnd;
            parseUna(una);
            shrinkBuf();
            //如果是ack
            if (IKCP_CMD_ACK == cmd) {
                if (iTimeDiff(current, ts) >= 0) {
                    updateAck(iTimeDiff(current, ts));
                }
                parseAck(sn);
                shrinkBuf();
            //如果是push
            } else if (IKCP_CMD_PUSH == cmd) {
                if (iTimeDiff(sn, rcvNxt + rcvWnd) < 0) {
                    ackPush(sn, ts);
                    if (iTimeDiff(sn, rcvNxt) >= 0) {
                        Segment seg = new Segment(data);
                        seg.conv = convTemp;
                        seg.cmd = cmd;
                        seg.frg = frg;
                        seg.wnd = wnd;
                        seg.ts = ts;
                        seg.sn = sn;
                        seg.una = una;
                        parseData(seg);
                    }
                }
            //如果是wask
            } else if (IKCP_CMD_WASK == cmd) {
                // ready to send back IKCP_CMD_WINS in Ikcp_flush
                // tell remote my window size
                probe |= IKCP_ASK_TELL;
            //如果是wins
            } else if (IKCP_CMD_WINS == cmd) {
                // do nothing
            } else {
                return -3;
            }
        }

        if (iTimeDiff(sndUna, sUna) > 0) {
            if (cwnd < rmtWnd) {
                long mssTemp = mss;
                if (cwnd < ssthresh) {
                    cwnd++;
                    incr += mssTemp;
                } else {
                    if (incr < mssTemp) {
                        incr = mssTemp;
                    }
                    incr += (mssTemp * mssTemp) / incr + (mssTemp / 16);
                    if ((cwnd + 1) * mssTemp <= incr) {
                        cwnd++;
                    }
                }
                if (cwnd > rmtWnd) {
                    cwnd = rmtWnd;
                    incr = rmtWnd * mssTemp;
                }
            }
        }
        return 0;
    }

    /**
     * 计算接收队列中有多少可用的数据
     * @return
     */
    public int peekSize() {
        if (0 == nrcvQue.size()) {
            //无数据
            return -1;
        }

        Segment seq = nrcvQue.get(0);

        if (0 == seq.frg) {
            //就这一个数据包就是一完整的数据
            return seq.data.readableBytes();
        }

        if (nrcvQue.size() < seq.frg + 1) {
            //数据不完整，还不能给上层
            return -1;
        }

        int length = 0;
        //取出一个完整的数据包length给上层
        for (Segment item : nrcvQue) {
            length += item.data.readableBytes();
            if (0 == item.frg) {
                break;
            }
        }

        return length;
    }
}
