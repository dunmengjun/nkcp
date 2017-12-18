package com.dun.nkcp;

import io.netty.buffer.ByteBuf;

public interface RecvListener {

    void recv(ByteBuf buf);
}
