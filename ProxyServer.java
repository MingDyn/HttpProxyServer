package com.ming;

import java.nio.charset.StandardCharsets;

abstract class ProxyServer {

    static final byte[] CONNECT_SUCCESS_RESPONSE = (
            "HTTP/1.1 200 Connection established\r\n" +
                    "Proxy-Agent: ProxyServer/1.0\r\n" +
                    "\r\n"
    ).getBytes(StandardCharsets.UTF_8);

    final int port;

    ProxyServer(int port) {
        this.port = port;
    }

    abstract void runServer();
}
