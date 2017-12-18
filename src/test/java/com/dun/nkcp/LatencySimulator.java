package com.dun.nkcp;

import io.netty.buffer.ByteBuf;

import java.net.InetSocketAddress;

/**
 * 网络模拟器
 */
public interface LatencySimulator {

    /**
     * 发送一个数据包
     * @param buf
     * @param address
     */
    void send(ByteBuf buf,InetSocketAddress address);



    void addRecvListener(RecvListener recvListener);
}
