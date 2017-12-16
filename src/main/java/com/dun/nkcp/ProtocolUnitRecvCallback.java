package com.dun.nkcp;

import io.netty.buffer.ByteBuf;

public interface ProtocolUnitRecvCallback {

    /**
     * 应用层接受完整数据包回调
     * @param byteBuf
     */
    void recv(ByteBuf byteBuf);
}
