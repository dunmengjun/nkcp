package com.dun.nkcp;

public interface ProtocolUnit<T> {

    int send(T buf);

    int recv(T buf);


}
