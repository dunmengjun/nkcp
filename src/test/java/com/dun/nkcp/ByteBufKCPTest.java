package com.dun.nkcp;


import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.CharsetUtil;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class ByteBufKCPTest {


    private final LatencySimulator simulator = new SimpleLatencySimulator(4);

    private ByteBufAllocator byteBufAllocator = new PooledByteBufAllocator();

    @Test
    public void testSend() throws InterruptedException {
        ByteBufKCP byteBufKCP = new OutHeapByteBufKCP(1001);

        byteBufKCP.setOutputCallback(byteBuf -> simulator.send(byteBuf,null));

        simulator.addRecvListener(buf -> {
            System.out.println("底层收到的包为:" + buf.readableBytes());
            byteBufKCP.input(buf);
        });

        byteBufKCP.setRecvCallback(byteBuf -> {
            System.out.println("收到结果了:" + byteBuf.readCharSequence(byteBuf.readableBytes(),CharsetUtil.UTF_8));
        });

        ByteBuf buf = byteBufAllocator.directBuffer();

        buf.writeCharSequence("hello world", CharsetUtil.UTF_8);

        byteBufKCP.send(buf);

        //给协议进行任务调度
        Thread thread = new Thread(() -> {
            while (true){
                long current = System.currentTimeMillis();
                long check = byteBufKCP.check(current);
                System.out.println("等待时间:" + (check - current));
                try {
                    TimeUnit.MILLISECONDS.sleep(check - current);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                byteBufKCP.update(System.currentTimeMillis());
            }
        });
        thread.start();
        //主线程休息10秒，看结果
        TimeUnit.MILLISECONDS.sleep(Long.MAX_VALUE);
    }
}
