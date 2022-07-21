package cc.nium.sni.io;

import cc.nium.sni.annotation.NotNull;
import cc.nium.sni.annotation.Nullable;
import cc.nium.sni.config.Config;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;

public final class SNISocket implements Closeable {

    private enum State {
        UnInitialized,
        Initializing,
        Normal,
        Closed
    }

    private enum ForwarderState {
        Ok,
        Idle,
        Error
    }

    private final SNIServerSocket server;
    private final Config config;
    private final String name;
    private final int dstPort;
    private final Proxy proxy;
    private final Socket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final Initializer initializer;
    private final Uploader uploader;
    private final Downloader downloader;
    private String serverName;
    private String link = "";
    private Socket upSocket;
    private InputStream upInputStream;
    private OutputStream upOutputStream;
    private volatile State state = State.UnInitialized;
    private volatile ForwarderState stateUpload = ForwarderState.Ok;
    private volatile ForwarderState stateDownload = ForwarderState.Ok;

    SNISocket(SNIServerSocket server, Config config, int dstPort, @NotNull final Proxy proxy, @NotNull final Socket socket) throws IOException {
        this.server = server;
        this.config = config;
        this.name = "@" + padding(Integer.toHexString(this.hashCode()));
        this.dstPort = dstPort;
        this.proxy = proxy;
        this.socket = socket;
        this.inputStream = socket.getInputStream();
        this.outputStream = socket.getOutputStream();
        socket.setSoTimeout(config.getIdleMillis());
        socket.setKeepAlive(false);
        socket.setTcpNoDelay(true);
        initializer = new Initializer();
        uploader = new Uploader();
        downloader = new Downloader();
    }

    private static String padding(String s) {
        return "00000000".substring(0, 8 - s.length()) + s;
    }

    @Override
    public synchronized void close() {
        if (state == State.Closed)
            return;
        state = State.Closed;
        server.remove(this);
        if (serverName != null)
            System.out.println("connections count = " + server.getConnectionNum() + " " + name + " " + link + " close");
        try {
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if (upSocket != null)
                upSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @NotNull
    Initializer getInitializer() {
        return initializer;
    }

    private static String parseClientHello(final ByteTemporaryBuffer byteBuffer, final int tlsPackageLength) throws IOException {
        final int handshakeProtocol = byteBuffer.read8Bit();
        if (handshakeProtocol != 0x01)
            throw new IOException("Is not Client Hello Handshake Protocol: " + handshakeProtocol);
        final int length = byteBuffer.read24Bit();
        if (tlsPackageLength != length + 4)
            throw new IOException("clientHelloBytes length not eq");
        @SuppressWarnings("unused") final int version = byteBuffer.read16Bit();
        // skip random bytes
        byteBuffer.skipLength(32);

        final int sessionIdLength = byteBuffer.read8Bit();
        // skip session id
        byteBuffer.skipLength(sessionIdLength);

        final int cipherSuitesLength = byteBuffer.read16Bit();
        // skip cipher suites
        byteBuffer.skipLength(cipherSuitesLength);

        final int compressionMethodsLength = byteBuffer.read8Bit();
        // skip compression methods
        byteBuffer.skipLength(compressionMethodsLength);

        final int extLength = byteBuffer.read16Bit();
        final int extStartIndex = byteBuffer.getReadIndex();
        if (extLength > 0) {
            while (byteBuffer.getReadIndex() - extStartIndex < extLength) {
                final int type = byteBuffer.read16Bit();
                @SuppressWarnings("unused") final int len = byteBuffer.read16Bit();
                if (type == 0) {// server name
                    final int serverNameListLength = byteBuffer.read16Bit();
                    final int serverNameStartIndex = byteBuffer.getReadIndex();
                    while (byteBuffer.getReadIndex() - serverNameStartIndex < serverNameListLength) {
                        final int serverNameListType = byteBuffer.read8Bit();
                        final int serverNameLength = byteBuffer.read16Bit();
                        if (serverNameListType == 0 && serverNameLength > 0) {
                            return byteBuffer.readString(serverNameLength);
                        } else {
                            // skip serverName
                            byteBuffer.skipLength(serverNameLength);
                        }
                    }
                }
            }
        }
        return null;
    }

    class Initializer implements Runnable {
        @Nullable
        public void run() {
            if (state != State.UnInitialized)
                return;
            state = State.Initializing;
            try {
                try (final ByteTemporaryBuffer byteBuffer = new ByteTemporaryBuffer(inputStream, config.getHeadBufferSize())) {
                    final int protocol = byteBuffer.read8Bit();
                    if (protocol != 0x16)
                        throw new IOException("Unknown Protocol: " + Integer.toHexString(protocol));
                    @SuppressWarnings("unused") final int subVersion = byteBuffer.read8Bit();
                    @SuppressWarnings("unused") final int mainVersion = byteBuffer.read8Bit();
                    final int length = byteBuffer.read16Bit();
                    if (length > 8192) {
                        throw new IOException("Client Hello Too Long: " + length);
                    }
                    final String serverName = SNISocket.this.serverName = parseClientHello(byteBuffer, length);
                    if (serverName == null) {
                        throw new IOException("Unknown serverName");
                    }
                    link = socket.getLocalPort() + " -> " + serverName + ":" + dstPort;
                    upSocket = new Socket(proxy);
                    upSocket.setSoTimeout(config.getIdleMillis());
                    upSocket.setKeepAlive(false);
                    upSocket.setTcpNoDelay(true);
                    InetSocketAddress dest = InetSocketAddress.createUnresolved(serverName, dstPort);
                    upSocket.connect(dest);
                    upInputStream = upSocket.getInputStream();
                    upOutputStream = upSocket.getOutputStream();
                    byteBuffer.transferBufferToAndClose(upOutputStream);
                }
                state = State.Normal;
                server.add(SNISocket.this);
                System.out.println("connections count = " + server.getConnectionNum() + " " + name + " " + link);
                // server.runForwarder(uploader);
                server.runForwarder(downloader);
                uploader.run();
            } catch (IOException e) {
                if (!(e instanceof SocketException) && !(e instanceof SocketTimeoutException))
                    e.printStackTrace();
                close();
            }
        }
    }

    class Uploader implements Forwarder {
        private final byte[] buffer = new byte[config.getForwarderBufferSize()];

        @Nullable
        @SuppressWarnings("Duplicates")
        public void run() {
            if (state != State.Normal || stateUpload == ForwarderState.Error)
                return;
            while (true) {
                try {
                    final int len = inputStream.read(buffer);
                    if (len < 0) {
                        socket.sendUrgentData(0);
                    } else {
                        if (stateUpload == ForwarderState.Idle)
                            System.out.println("connections count = " + server.getConnectionNum() + " " + name + " " + link + " ↑ continue");
                        stateUpload = ForwarderState.Ok;
                        upOutputStream.write(buffer, 0, len);
                        upOutputStream.flush();
                    }
                } catch (SocketTimeoutException e) {
                    try {
                        socket.sendUrgentData(0);
                    } catch (IOException ex) {
                        error(ex);
                        return;
                    }
                    if (stateUpload == ForwarderState.Ok)
                        System.out.println("connections count = " + server.getConnectionNum() + " " + name + " " + link + " ↑ idle");
                    stateUpload = ForwarderState.Idle;
                } catch (IOException e) {
                    error(e);
                    return;
                }
            }
        }

        private void error(IOException e) {
            stateUpload = ForwarderState.Error;
            if (socket.isClosed())
                return;
            System.out.println("connections count = " + server.getConnectionNum() + " " + name + " " + link + " ↑ " + e.getClass().getName() + ": " + e.getMessage());
            if (!(e instanceof SocketException) && !(e instanceof SocketTimeoutException))
                e.printStackTrace();
            close();
        }
    }


    public class Downloader implements Forwarder {
        private final byte[] buffer = new byte[config.getForwarderBufferSize()];

        @Nullable
        @SuppressWarnings("Duplicates")
        public void run() {
            if (state != State.Normal || stateDownload == ForwarderState.Error)
                return;
            while (true) {
                try {
                    final int len = upInputStream.read(buffer);
                    if (len < 0) {
                        upSocket.sendUrgentData(0);
                    } else {
                        if (stateDownload == ForwarderState.Idle)
                            System.out.println("connections count = " + server.getConnectionNum() + " " + name + " " + link + " ↓ continue");
                        stateDownload = ForwarderState.Ok;
                        outputStream.write(buffer, 0, len);
                        outputStream.flush();
                    }
                } catch (SocketTimeoutException e) {
                    try {
                        upSocket.sendUrgentData(0);
                    } catch (IOException ex) {
                        error(ex);
                        return;
                    }
                    if (stateDownload == ForwarderState.Ok)
                        System.out.println("connections count = " + server.getConnectionNum() + " " + name + " " + link + " ↓ idle");
                    stateDownload = ForwarderState.Idle;
                } catch (IOException e) {
                    error(e);
                    return;
                }
            }
        }

        private void error(IOException e) {
            stateDownload = ForwarderState.Error;
            if (socket.isClosed())
                return;
            System.out.println("connections count = " + server.getConnectionNum() + " " + name + " " + link + " ↓ " + e.getClass().getName() + ": " + e.getMessage());
            if (!(e instanceof SocketException) && !(e instanceof SocketTimeoutException))
                e.printStackTrace();
            close();
        }
    }
}
