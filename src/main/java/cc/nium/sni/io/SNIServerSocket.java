package cc.nium.sni.io;

import cc.nium.sni.annotation.NotNull;
import cc.nium.sni.config.Config;
import cc.nium.sni.config.ServerConfig;
import cc.nium.sni.util.ConcurrentHashSet;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.ExecutorService;

public final class SNIServerSocket implements Closeable {

    private final ExecutorService pool;
    private final ConcurrentHashSet<SNISocket> allSockets;
    private final Config config;
    private final int dstPort;
    private final Proxy proxy;
    private final ServerSocket serverSocket;

    public SNIServerSocket(ExecutorService pool, ConcurrentHashSet<SNISocket> allSockets, Config config, ServerConfig serverConfig) throws IOException {
        this.pool = pool;
        this.allSockets = allSockets;
        this.config = config;

        final String proxyTypeStr = serverConfig.getProxyType();
        final Proxy.Type proxyType;
        if ("socks".equalsIgnoreCase(proxyTypeStr)) {
            proxyType = Proxy.Type.SOCKS;
        } else if ("http".equalsIgnoreCase(proxyTypeStr)) {
            proxyType = Proxy.Type.HTTP;
        } else {
            throw new RuntimeException("unknown proxy type: \"" + proxyTypeStr + "\", available: [\"socks\", \"http\"]");
        }

        final String proxyHostStr = serverConfig.getProxyHost();
        final InetAddress proxyHost = InetAddress.getByName(proxyHostStr);
        final int proxyPort = serverConfig.getProxyPort();
        if (proxyPort <= 0 || proxyPort > 65535) {
            throw new RuntimeException("proxyPort " + proxyPort + " out of range: (0, 65535]");
        }
        this.proxy = new Proxy(proxyType, new InetSocketAddress(proxyHost, proxyPort));

        final String bindHostStr = serverConfig.getBindHost();
        final InetAddress bindHost = InetAddress.getByName(bindHostStr);
        final int bindPort = serverConfig.getBindPort();
        if (bindPort <= 0 || bindPort > 65535) {
            throw new RuntimeException("bindPort " + bindPort + " out of range: (0, 65535]");
        }
        serverSocket = new ServerSocket(bindPort, 50, bindHost);
        serverSocket.setSoTimeout(10);

        this.dstPort = serverConfig.getDstPort();
        if (dstPort <= 0 || dstPort > 65535) {
            throw new RuntimeException("dstPort " + dstPort + " out of range: (0, 65535]");
        }

        System.out.println("proxyType = " + proxyTypeStr);
        System.out.println("proxyHost = " + proxyHostStr);
        System.out.println("proxyPort = " + proxyPort);
        System.out.println("bindHost  = " + bindHostStr);
        System.out.println("bindPort  = " + bindPort);
        System.out.println("dstPort   = " + dstPort);
        System.out.println("========================================");
    }

    @SuppressWarnings("EmptyCatchBlock")
    public void accept() throws IOException {
        try {
            final SNISocket socket = new SNISocket(this, config, dstPort, proxy, serverSocket.accept());
            pool.execute(socket.getInitializer());
        } catch (SocketTimeoutException e) {
        }
    }

    int getConnectionNum() {
        return allSockets.size();
    }

    void add(SNISocket socket) {
        allSockets.add(socket);
    }

    void remove(SNISocket socket) {
        allSockets.remove(socket);
    }

    void runForwarder(@NotNull final Forwarder forwarder) {
        pool.execute(forwarder);
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
