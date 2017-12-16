package com.dun.nkcp;

import io.netty.buffer.ByteBuf;

public interface ProtocolUnitOutputCallback {

    /**
     * 发送消息
     * @param byteBuf
     */
    void output(ByteBuf byteBuf);
}
