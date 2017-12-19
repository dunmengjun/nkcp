package com.dun.nkcp;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SimpleLatencySimulator implements LatencySimulator {

    private final Logger LOGGER = LoggerFactory.getLogger(SimpleLatencySimulator.class);

    private List<RecvListener> listeners;

    private final Queue<ByteBuf> queue = new ConcurrentLinkedDeque<>();

    private ExecutorService executorService;

    public SimpleLatencySimulator(int threadSize){
        listeners= new ArrayList<>(5);
        executorService = Executors.newFixedThreadPool(threadSize);
        executorService.submit(new QueueListenTask());
//        executorService.submit(new QueueListenTask());
//        executorService.submit(new QueueListenTask());
//        executorService.submit(new QueueListenTask());

    }

    @Override
    public void send(ByteBuf buf, InetSocketAddress address) {
        queue.offer(buf.readBytes(buf.readableBytes()));
    }

    @Override
    public void addRecvListener(RecvListener recvListener) {
        listeners.add(recvListener);
    }

    class QueueListenTask implements Runnable{
        @Override
        public void run() {
            Random random = new Random();
            while (true){
                try {
                    ByteBuf peek = queue.poll();
                    int re = random.nextInt(10);
                    if(peek == null){
                        TimeUnit.MILLISECONDS.sleep(1000);
                    }else{
                        //模拟丢包率
                        if(re == 0){
                            System.out.println("模拟丢包率 丢弃消息");
                            TimeUnit.MILLISECONDS.sleep(50);
                            break;
                        }
                        if(!listeners.isEmpty()){
                            System.out.println("收到消息,进行回调 kcp的input");
                            for(RecvListener listener : listeners){
                                listener.recv(peek);
                            }
                        }
                        TimeUnit.MILLISECONDS.sleep(50);
                    }
                } catch (InterruptedException e) {
                    LOGGER.error(e.getMessage(),e);
                }
            }
        }
    }
}
