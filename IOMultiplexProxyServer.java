package com.ming;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

public class IOMultiplexProxyServer extends ProxyServer{

    private final Selector selector;

    class SocketChannelAtt {

        private final static int BUFFER_SIZE = 2048;

        private boolean isClient;
        private ByteBuffer byteBuffer;
        private SelectionKey counterpartSlctKey;
        private State state;

        public HttpRequest httpRequest;
        public HttpResponse httpResponse;

        private int bytesWritten;
        private int bytesRead;

        enum State {
            IDLE,
            GET,
            CONNECT
        }

        SocketChannelAtt() {
            this.byteBuffer = ByteBuffer.allocate(BUFFER_SIZE);
            this.state = State.IDLE;
        }

        SocketChannelAtt(boolean isClient) {
            this();
            this.isClient = isClient;
        }

        public void setCounterpartSlctKey(SelectionKey selectionKey) {
            counterpartSlctKey = selectionKey;
        }

        public SelectionKey getCounterpartSlctKey() {
            return counterpartSlctKey;
        }

        public boolean isClient() {
            return isClient;
        }

        public ByteBuffer getByteBuffer() {
            return byteBuffer;
        }

        public void changeByteBufferTo(ByteBuffer byteBuffer) {
            this.byteBuffer = byteBuffer;
        }

        public boolean handleHttpRequest() {
            int pos = byteBuffer.position();

            int len = HttpRequest.endOfEmptyLine(byteBuffer.array(), pos);
            if (len > 0) {
                httpRequest = new HttpRequest().parseRequest(new String(byteBuffer.array(), 0, len));

                switch (httpRequest.type) {
                    case HttpRequest.GET_TYPE -> state = State.GET;
                    case HttpRequest.CONNECT_TYPE -> state = State.CONNECT;
                    default -> System.out.println("This HTTP type of " + httpRequest.type + " is not supported!");
                }

                byteBuffer.clear();

                return true;
            }

            return false;
        }

        public boolean handleHttpResponse() {
            int pos = byteBuffer.position();

            int len = HttpResponse.endOfEmptyLine(byteBuffer.array(), pos);
            if (len > 0) {
                httpResponse = new HttpResponse().parseResponse(new String(byteBuffer.array(), 0, len));

                return true;
            }

            return false;
        }

        public void updateBytesRead(int num) {
            bytesRead += num;
        }

        public void updateBytesWritten(int num) {
            bytesWritten += num;
        }

        public boolean isReadComplete() {
            return httpResponse != null && bytesRead == httpResponse.hdrSize + httpResponse.contentLen;
        }

        public boolean isWriteComplete() {
            return httpResponse != null && bytesWritten == httpResponse.hdrSize + httpResponse.contentLen;
        }

        public void reset() {
            bytesRead = 0;
            bytesWritten = 0;
            httpResponse = null;
            byteBuffer.clear();
        }
    }

    IOMultiplexProxyServer(int port) throws IOException {
        super(port);
        this.selector = Selector.open();
    }

    @Override
    void runServer() {
        try {
            ServerSocketChannel serverSocket = ServerSocketChannel.open();
            SocketAddress socketAddress = new InetSocketAddress("localhost", port);
            serverSocket.bind(socketAddress);

            serverSocket.configureBlocking(false);

            serverSocket.register(selector, SelectionKey.OP_ACCEPT);

            while (true) {
                select();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void select() {
        // non-blocking select
        try {
            selector.select();

            Set selectedKeys = selector.selectedKeys();
            Iterator iterator = selectedKeys.iterator();
            while (iterator.hasNext()) {
                SelectionKey selectionKey = (SelectionKey) iterator.next();
                iterator.remove();

                if (!selectionKey.isValid()) {
                    // socket is closed
                    continue;
                }

                if (selectionKey.isAcceptable()) {
                    handleAccept(selectionKey);
                } else if (selectionKey.isReadable()) {
                    handleRead(selectionKey);
                } else if (selectionKey.isWritable()){
                    handleWrite(selectionKey);
                } else {
                    System.out.println("Error, not implemented");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleAccept(SelectionKey selectionKey) {
        ServerSocketChannel socketChannel = (ServerSocketChannel) selectionKey.channel();
        SocketChannel clientSocket = null;

        try {
            clientSocket = socketChannel.accept();

            if (clientSocket == null) {
                System.out.println("accept client socket is null");
                return;
            }

            System.out.println("http request accepted");

            clientSocket.configureBlocking(false);

            clientSocket.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE)
                    .attach(new SocketChannelAtt(true));
        } catch (ClosedChannelException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleRead(SelectionKey selectionKey) {
        SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
        SocketChannelAtt attachment = (SocketChannelAtt) selectionKey.attachment();

        ByteBuffer byteBuffer = attachment.getByteBuffer();

        // buffer is full (data in the buffer is not written yet)
        if (!byteBuffer.hasRemaining())
            return;

        // read to buffer
        int bytesRead = 0;
        try {
            bytesRead = socketChannel.read(byteBuffer);
        } catch (IOException e) {
            closeSocketChannel(socketChannel);
        }

        if (attachment.isClient()) {
            handleClientRead(selectionKey, attachment, byteBuffer);
        } else {
            handleServerRead(attachment, bytesRead);
        }
    }

    private void handleClientRead(SelectionKey selectionKey, SocketChannelAtt attachment, ByteBuffer byteBuffer) {
        switch (attachment.state) {
            case IDLE -> {
                HttpRequest oldHttpRequest = attachment.httpRequest;

                boolean isValidRequest = attachment.handleHttpRequest();

                HttpRequest newHttpRequest = attachment.httpRequest;

                if (isValidRequest) {
                    // check if a server socket can be reused
                    if (!isTheSameTargetAddr(oldHttpRequest, newHttpRequest)) {
                        // create a socket to connect the target server, register the socket
                        SelectionKey serverSelectionKey = registerServerSocketChannel(newHttpRequest);

                        // fail to connect the target server
                        if (serverSelectionKey == null) {
                            closeSocketChannel((SocketChannel) selectionKey.channel());

                            return;
                        }

                        // set selection key for both parts
                        ((SocketChannelAtt) serverSelectionKey.attachment()).setCounterpartSlctKey(selectionKey);
                        attachment.setCounterpartSlctKey(serverSelectionKey);
                    }

                    // reset the buffer
                    byteBuffer.clear();

                    // set the state to GET / CONNECT and initialize server socket buffer
                    switch (newHttpRequest.type) {
                        case HttpRequest.GET_TYPE -> {
                            attachment.state = SocketChannelAtt.State.GET;

                            byteBuffer.put(attachment.httpRequest.buildGetRequest().getBytes(StandardCharsets.UTF_8));
                        }
                        case HttpRequest.CONNECT_TYPE -> {
                            attachment.state = SocketChannelAtt.State.CONNECT;

                            SelectionKey counterpartSlctKey = attachment.getCounterpartSlctKey();
                            SocketChannelAtt counterpartAtt = (SocketChannelAtt) counterpartSlctKey.attachment();
                            ByteBuffer counterpartBuffer = counterpartAtt.getByteBuffer();
                            counterpartBuffer.put(CONNECT_SUCCESS_RESPONSE);
                        }
                        default -> throw new IllegalStateException("Unexpected http request type: " + newHttpRequest.type);
                    }
                } else if (!byteBuffer.hasRemaining()) {
                    // HTTP request size is larger than the buffer size
                    // grow the buffer size to 2x
                    // (rarely happen for GET and CONNECT request using a 2048 bytes buffer)
                    int oldCap = byteBuffer.capacity();
                    ByteBuffer newByteBuffer = ByteBuffer.wrap(Arrays.copyOf(byteBuffer.array(), oldCap << 1))
                            .position(oldCap);

                    attachment.changeByteBufferTo(newByteBuffer);
                }

                // else: not changing state, nor growing the buffer (http request is not complete yet)
            }
            case GET -> {} // do nothing
            case CONNECT -> {} // do nothing
            default -> {}
        }
    }

    private void handleServerRead(SocketChannelAtt attachment, int bytesRead) {
        attachment.updateBytesRead(bytesRead);

        switch (attachment.state) {
            case IDLE -> {} // server never has read() when IDLE
            case GET -> { // parse response hdr
                if (attachment.httpResponse == null) {
                    // if null, always try to get the complete response hdr
                    attachment.handleHttpResponse();
                } else if (attachment.isReadComplete()) {
                    System.out.println("Get read complete: " + attachment.bytesRead);

                    attachment.state = SocketChannelAtt.State.IDLE;
                }
            }
            case CONNECT -> {} // do nothing
            default -> {}
        }
    }

    private void handleWrite(SelectionKey selectionKey) {
        SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
        SocketChannelAtt attachment = (SocketChannelAtt) selectionKey.attachment();

        SelectionKey counterpartSlctKey = attachment.getCounterpartSlctKey();
        if (counterpartSlctKey == null) {
            // client is not ready for read yet
            return;
        } else if (!counterpartSlctKey.isValid()) {
            if (attachment.isClient()) System.out.println("server closed the connection");

            // the connection is closed on the other end
            closeSocketChannel(socketChannel);
            return;
        }

        SocketChannelAtt counterpartAtt = (SocketChannelAtt) counterpartSlctKey.attachment();

        ByteBuffer counterpartBuffer = counterpartAtt.getByteBuffer();

        int toBeWritten = counterpartBuffer.position();
        int bytesWritten = 0;
        if (toBeWritten > 0) {
            counterpartBuffer.flip();

            try {
                bytesWritten = socketChannel.write(counterpartBuffer);
            } catch (IOException e) {
                e.printStackTrace();
            }

            counterpartBuffer.compact(); // in case of partial write\
        }

        if (attachment.isClient())
            handleClientWrite(selectionKey, bytesWritten);
    }

    private void handleClientWrite(SelectionKey clientSlctKey, int bytesWritten) {
        SocketChannelAtt clientAtt = (SocketChannelAtt) clientSlctKey.attachment();

        SelectionKey counterpartSlctKey = clientAtt.getCounterpartSlctKey();
        SocketChannelAtt serverAtt = (SocketChannelAtt) counterpartSlctKey.attachment();

        serverAtt.updateBytesWritten(bytesWritten);
        if (serverAtt.isWriteComplete()) {
            System.out.println("Get write complete: " + serverAtt.bytesWritten);

            clientAtt.state = SocketChannelAtt.State.IDLE;

            serverAtt.reset();
        }
    }

    private boolean isTheSameTargetAddr(HttpRequest oldRequest, HttpRequest newRequest) {
        return oldRequest != null &&
                oldRequest.headerMap.get(HttpRequest.HOST).equals(newRequest.headerMap.get(HttpRequest.HOST)) &&
                oldRequest.port == newRequest.port;
    }

    private SelectionKey registerServerSocketChannel(HttpRequest httpRequest) {
        InetSocketAddress addr = new InetSocketAddress(httpRequest.headerMap.get(HttpRequest.HOST), httpRequest.port);
        SelectionKey selectionKey = null;

        try {
            SocketChannel socketChannel = SocketChannel.open();

            // connect with timeout
            try {
                socketChannel.socket().connect(addr, 500);
            } catch (SocketTimeoutException e) {
                return null;
            }
            // TODO: nonblocking connect with OP_CONNECT

            socketChannel.configureBlocking(false);
            selectionKey = socketChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

            SocketChannelAtt attachment = new SocketChannelAtt(false);
            attachment.state = switch (httpRequest.type) {
                case HttpRequest.GET_TYPE -> SocketChannelAtt.State.GET;
                case HttpRequest.CONNECT_TYPE -> SocketChannelAtt.State.CONNECT;
                default -> throw new IllegalStateException("Unexpected http request type:"  + httpRequest.type);
            };

            selectionKey.attach(attachment);
        } catch (ClosedChannelException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return selectionKey;
    }

    private void closeSocketChannel(SocketChannel socketChannel) {
        try {
            socketChannel.close();
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }
}
