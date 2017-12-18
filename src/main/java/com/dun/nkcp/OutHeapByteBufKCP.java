package com.dun.nkcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

public class OutHeapByteBufKCP extends ByteBufKCP{

    private ByteBufAllocator bufAllocator;

    public OutHeapByteBufKCP(int conv) {
        super(conv);
        this.bufAllocator = new PooledByteBufAllocator();
        this.buffer = byteBuffer((int) (mtu + IKCP_OVERHEAD) * 3);
    }

    @Override
    protected ByteBuf byteBuffer(int size) {
        return bufAllocator.directBuffer(size);
    }

    @Override
    protected CompositeByteBuf compositeBuffer(int size) {
        return bufAllocator.compositeBuffer(size);
    }
}
