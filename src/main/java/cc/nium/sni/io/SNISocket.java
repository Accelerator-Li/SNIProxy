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
        Error,
        Closed
    }

    private enum ForwarderState {
        Running,
        Idle
    }

    private enum Direction {
        Up("↑"),
        Down("↓"),
        ;

        @NotNull
        private static final String nullMark = "  ";
        private final String mark;

        Direction(@NotNull final String mark) {
            this.mark = mark;
        }

        @NotNull
        @Override
        public String toString() {
            return mark;
        }

        @NotNull
        public static String toString(@Nullable final Direction direction) {
            return direction == null ? nullMark : direction.toString();
        }
    }

    private final SNIServerSocket server;
    private final Config config;
    private final String name;
    private final int dstPort;
    private final Proxy proxy;
    private final Socket localSocket;
    private final InputStream localInputStream;
    private final OutputStream localOutputStream;
    private final Initializer initializer;
    private final Uploader uploader;
    private final Downloader downloader;
    private String serverName;
    private String link = "";
    private Socket upperSocket;
    private InputStream upperInputStream;
    private OutputStream upperOutputStream;
    private volatile State state = State.UnInitialized;
    private volatile ForwarderState stateUpload = ForwarderState.Running;
    private volatile ForwarderState stateDownload = ForwarderState.Running;

    SNISocket(SNIServerSocket server, Config config, int dstPort, @NotNull final Proxy proxy, @NotNull final Socket localSocket) throws IOException {
        this.server = server;
        this.config = config;
        this.name = "@" + padding(Integer.toHexString(this.hashCode()));
        this.dstPort = dstPort;
        this.proxy = proxy;
        this.localSocket = localSocket;
        this.localInputStream = localSocket.getInputStream();
        this.localOutputStream = localSocket.getOutputStream();
        localSocket.setSoTimeout(config.getIdleMillis());
        localSocket.setKeepAlive(false);
        localSocket.setTcpNoDelay(true);
        localSocket.setSoLinger(true, 0);
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
            localSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if (upperSocket != null)
                upperSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void close(@NotNull final IOException e) {
        if (!(e instanceof SocketException) && !(e instanceof SocketTimeoutException))
            e.printStackTrace();
        close();
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

    private void localError(@NotNull final IOException e, @Nullable final Direction direction) {
        if (state == State.Normal)
            state = State.Error;
        if (localSocket.isClosed())
            return;
        System.out.println("connections count = " + server.getConnectionNum() + " " + name + " " + link + " local " + Direction.toString(direction) + " " + e.getClass().getName() + ": " + e.getMessage());
        close(e);
    }

    private void upperError(@NotNull final IOException e, @Nullable final Direction direction) {
        if (state == State.Normal)
            state = State.Error;
        if (upperSocket.isClosed())
            return;
        System.out.println("connections count = " + server.getConnectionNum() + " " + name + " " + link + " upper " + Direction.toString(direction) + " " + e.getClass().getName() + ": " + e.getMessage());
        close(e);
    }

    private boolean checkLocal() {
        try {
            localSocket.sendUrgentData(0);
            return false;
        } catch (IOException e) {
            localError(e, null);
            return true;
        }
    }

    private boolean checkUpper() {
        try {
            upperSocket.sendUrgentData(0);
            return false;
        } catch (IOException e) {
            upperError(e, null);
            return true;
        }
    }

    class Initializer implements Runnable {

        @Override
        public void run() {
            if (state != State.UnInitialized)
                return;
            state = State.Initializing;
            try {
                try (final ByteTemporaryBuffer byteBuffer = new ByteTemporaryBuffer(localInputStream, config.getHeadBufferSize() + 5)) {// 5 bytes for (protocol, version, length) bytes
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
                    link = localSocket.getLocalPort() + " -> " + serverName + ":" + dstPort;
                    upperSocket = new Socket(proxy);
                    upperSocket.setSoTimeout(config.getIdleMillis());
                    upperSocket.setKeepAlive(false);
                    upperSocket.setTcpNoDelay(true);
                    upperSocket.setSoLinger(true, 0);
                    InetSocketAddress dest = InetSocketAddress.createUnresolved(serverName, dstPort);
                    upperSocket.connect(dest);
                    upperInputStream = upperSocket.getInputStream();
                    upperOutputStream = upperSocket.getOutputStream();
                    byteBuffer.transferBufferToAndClose(upperOutputStream);
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

        @Override
        public void run() {
            if (state != State.Normal)
                return;
            while (true) {
                try {
                    final int len = localInputStream.read(buffer);
                    if (len < 0) {
                        if (checkLocal())
                            return;
                    } else {
                        if (stateUpload == ForwarderState.Idle)
                            System.out.println("connections count = " + server.getConnectionNum() + " " + name + " " + link + " local " + Direction.Up + " continue");
                        stateUpload = ForwarderState.Running;
                        try {
                            upperOutputStream.write(buffer, 0, len);
                            upperOutputStream.flush();
                        } catch (IOException e) {
                            upperError(e, Direction.Up);
                            return;
                        }
                    }
                } catch (SocketTimeoutException e) {
                    if (checkLocal())
                        return;
                    if (stateUpload == ForwarderState.Running)
                        System.out.println("connections count = " + server.getConnectionNum() + " " + name + " " + link + " local " + Direction.Up + " idle");
                    stateUpload = ForwarderState.Idle;
                } catch (IOException e) {
                    localError(e, Direction.Up);
                    return;
                }
            }
        }
    }


    public class Downloader implements Forwarder {
        private final byte[] buffer = new byte[config.getForwarderBufferSize()];

        @Override
        public void run() {
            if (state != State.Normal)
                return;
            while (true) {
                try {
                    final int len = upperInputStream.read(buffer);
                    if (len < 0) {
                        if (checkUpper())
                            return;
                    } else {
                        if (stateDownload == ForwarderState.Idle)
                            System.out.println("connections count = " + server.getConnectionNum() + " " + name + " " + link + " upper " + Direction.Down + " continue");
                        stateDownload = ForwarderState.Running;
                        try {
                            localOutputStream.write(buffer, 0, len);
                            localOutputStream.flush();
                        } catch (IOException e) {
                            localError(e, Direction.Down);
                            return;
                        }
                    }
                } catch (SocketTimeoutException e) {
                    if (checkUpper())
                        return;
                    if (stateDownload == ForwarderState.Running)
                        System.out.println("connections count = " + server.getConnectionNum() + " " + name + " " + link + " upper " + Direction.Down + " idle");
                    stateDownload = ForwarderState.Idle;
                } catch (IOException e) {
                    upperError(e, Direction.Down);
                    return;
                }
            }
        }
    }
}
