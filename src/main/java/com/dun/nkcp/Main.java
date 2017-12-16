package com.dun.nkcp;

import io.netty.buffer.*;
import io.netty.util.ReferenceCountUtil;

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
        //丢弃读过的字节
        byteBuf.discardReadBytes();
        //在读索引超过一半的时候才进行前移操作
        byteBuf.discardSomeReadBytes();
        byteBuf.release();

        CompositeByteBuf compositeByteBuf = byteBufAllocator.compositeBuffer(20);
    }
}
