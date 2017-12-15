package com.dun.nkcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.ArrayList;
import java.util.List;

public class ByteBufKCP {

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

    long sndUna = 0;
    long conv = 0;
    long sndNxt = 0;
    long current = 0;
    long rxSrtt = 0;
    long rxRttval = 0;
    long rcvNxt = 0;
    long probe = 0;
    long cwnd = 0;
    long incr = 0;
    long ssthresh = IKCP_THRESH_INIT;
    long mtu = IKCP_MTU_DEF;
    long mss = this.mtu - IKCP_OVERHEAD;
    long rcvWnd = IKCP_WND_RCV;
    long rxMinrto = IKCP_RTO_MIN;
    long rxRto = IKCP_RTO_DEF;
    long rmtWnd = IKCP_WND_RCV;

    ArrayList<Segment> nsndBuf = new ArrayList<>(128);
    ArrayList<Long> ackList = new ArrayList<>(128);
    ArrayList<Segment> nrcvBuf = new ArrayList<>(128);
    ArrayList<Segment> nrcvQue = new ArrayList<>(128);


    private ByteBufAllocator bufAllocator;


    public ByteBufKCP(ByteBufAllocator byteBufAllocator,int conv){
        this.bufAllocator = byteBufAllocator;
        this.conv = conv;
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
     * @param offset
     * @return
     */
    public static long iKcpDecode32u(ByteBuf data, int offset) {
        long ret = (data.getByte(offset) & 0xFFL) << 24
                | (data.getByte(offset + 1) & 0xFFL) << 16
                | (data.getByte(offset + 2) & 0xFFL) << 8
                | data.getByte(offset + 3) & 0xFFL;
        return ret;
    }

    /**
     * decode 8 bits unsigned int
     * @param data
     * @param offset
     * @return
     */
    public static byte iKcpDecode8u(ByteBuf data, int offset) {
        return data.getByte(offset);
    }

    /**
     * decode 16 bits unsigned int (msb)
     * @param data
     * @param offset
     * @return
     */
    public static int iKcpDecode16u(ByteBuf data, int offset) {
        int ret = (data.getByte(offset) & 0xFF) << 8
                | (data.getByte(offset + 1) & 0xFF);
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

    //---------------------------------------------------------------------
    // parse ack
    //---------------------------------------------------------------------
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

    //---------------------------------------------------------------------
    // parse data
    //---------------------------------------------------------------------
    // 用户数据包解析
    void parseData(Segment newseg) {
        long sn = newseg.sn;
        boolean repeat = false;

        if (iTimeDiff(sn, rcvNxt + rcvWnd) >= 0 || iTimeDiff(sn, rcvNxt) < 0) {
            return;
        }

        int n = nrcvBuf.size() - 1;
        int afterIdx = -1;

        // 判断是否是重复包，并且计算插入位置
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
                nrcvBuf.add(0, newseg);
            } else {
                nrcvBuf.add(afterIdx + 1, newseg);
            }
        }

        // move available data from nrcv_buf -> nrcv_que
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
    }

    /**
     * 底层收包后调用，再由上层通过Recv获得处理后的数据
     * @param data
     * @return
     */
    public int input(ByteBuf data){
        long sUna = sndUna;
        if (data.readableBytes() < IKCP_OVERHEAD) {
            return 0;
        }
        int offset = 0;
        while (true) {
            long ts, sn, length, una, convTemp;
            int wnd;
            byte cmd, frg;

            if (data.readableBytes() - offset < IKCP_OVERHEAD) {
                break;
            }

            convTemp = iKcpDecode32u(data, offset);
            offset += 4;
            if (conv != convTemp) {
                return -1;
            }

            cmd = iKcpDecode8u(data, offset);
            offset += 1;
            frg = iKcpDecode8u(data, offset);
            offset += 1;
            wnd = iKcpDecode16u(data, offset);
            offset += 2;
            ts = iKcpDecode32u(data, offset);
            offset += 4;
            sn = iKcpDecode32u(data, offset);
            offset += 4;
            una = iKcpDecode32u(data, offset);
            offset += 4;
            length = iKcpDecode32u(data, offset);
            offset += 4;

            if (data.readableBytes() - offset < length) {
                return -2;
            }

            if (cmd != IKCP_CMD_PUSH && cmd != IKCP_CMD_ACK && cmd != IKCP_CMD_WASK && cmd != IKCP_CMD_WINS) {
                return -3;
            }

            rmtWnd = (long) wnd;
            parseUna(una);
            shrinkBuf();

            if (IKCP_CMD_ACK == cmd) {
                if (iTimeDiff(current, ts) >= 0) {
                    updateAck(iTimeDiff(current, ts));
                }
                parseAck(sn);
                shrinkBuf();
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
            } else if (IKCP_CMD_WASK == cmd) {
                // ready to send back IKCP_CMD_WINS in Ikcp_flush
                // tell remote my window size
                probe |= IKCP_ASK_TELL;
            } else if (IKCP_CMD_WINS == cmd) {
                // do nothing
            } else {
                return -3;
            }

            offset += (int) length;
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
}
