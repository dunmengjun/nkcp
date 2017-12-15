package com.dun.nkcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.PooledByteBufAllocator;

import java.io.UnsupportedEncodingException;

public class Main {

    public static void main(String[] args) throws UnsupportedEncodingException {
        ByteBufAllocator byteBufAllocator = new PooledByteBufAllocator();
        ByteBuf byteBuf = byteBufAllocator.directBuffer(16);
        byteBuf.writeBytes("hello world".getBytes("UTF-8"));
        if(!byteBuf.hasArray()){
            int len = byteBuf.readableBytes();
            byte[] copyArray = new byte[len];
            byteBuf.getBytes(0,copyArray);
            System.out.println(new String(copyArray,"UTF-8"));
        }

    }
}
