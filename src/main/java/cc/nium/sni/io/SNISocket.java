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

    private enum Item {
        Arrive,
        Parse,
        Local,
        Upper,
        Close,
        ;

        @NotNull
        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    private enum ForwarderState {
        Running,
        Idle,
        ;

        @NotNull
        @Override
        public String toString() {
            return name().toLowerCase();
        }
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

    private static final int soTimeout = 5000;
    private final SNIServerSocket server;
    private final Config config;
    private final String id;
    private final int dstPort;
    private final Proxy proxy;
    private final Socket localSocket;
    private final InputStream localInputStream;
    private final OutputStream localOutputStream;
    private final Initializer initializer;
    private final Uploader uploader;
    private final Downloader downloader;
    private String sniName;
    private String linkName;
    private Socket upperSocket;
    private InputStream upperInputStream;
    private OutputStream upperOutputStream;
    private volatile State state = State.UnInitialized;
    private volatile ForwarderState stateUpload = ForwarderState.Running;
    private volatile ForwarderState stateDownload = ForwarderState.Running;

    SNISocket(SNIServerSocket server, Config config, int dstPort, @NotNull final Proxy proxy, @NotNull final Socket localSocket) throws IOException {
        this.server = server;
        this.config = config;
        this.id = "@" + padding(Integer.toHexString(this.hashCode()));
        this.dstPort = dstPort;
        this.proxy = proxy;
        this.localSocket = localSocket;
        this.localInputStream = localSocket.getInputStream();
        this.localOutputStream = localSocket.getOutputStream();
        localSocket.setSoTimeout(soTimeout);
        localSocket.setKeepAlive(false);
        localSocket.setTcpNoDelay(true);
        localSocket.setSoLinger(true, 0);
        linkName = localSocket.getInetAddress().getHostAddress() + ":" + localSocket.getPort() + " -> " + localSocket.getLocalPort();
        initializer = new Initializer();
        uploader = new Uploader();
        downloader = new Downloader();
        server.add(this);
        log(Item.Arrive);
    }

    private static String padding(String s) {
        return "00000000".substring(0, 8 - s.length()) + s;
    }

    @SuppressWarnings("unused")
    public String getSniName() {
        return sniName;
    }

    @Override
    public synchronized void close() {
        if (state == State.Closed)
            return;
        state = State.Closed;
        server.remove(this);
        log(Item.Close);
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

    private synchronized void error(@NotNull final Item item, @NotNull final IOException e, @Nullable final Direction direction) {
        if (state == State.Closed)
            return;
        state = State.Error;
        if (upperSocket.isClosed())
            return;
        System.out.format("connections count = %d %s %s %s %s %s: %s\n", server.getConnectionNum(), id, linkName, item, Direction.toString(direction), e.getClass().getName(), e.getMessage());
        if (!(e instanceof SocketException) && !(e instanceof SocketTimeoutException))
            e.printStackTrace();
        close();
    }

    private void log(@NotNull final Item item) {
        System.out.format("connections count = %d %s %s %s\n", server.getConnectionNum(), id, linkName, item);
    }

    private void log(@NotNull final Item item, @NotNull final Direction direction, @NotNull final ForwarderState forwarderState) {
        System.out.format("connections count = %d %s %s %s %s %s\n", server.getConnectionNum(), id, linkName, item, Direction.toString(direction), forwarderState);
    }

    private boolean checkLocal() {
        try {
            localSocket.sendUrgentData(0);
            return false;
        } catch (IOException e) {
            error(Item.Local, e, null);
            return true;
        }
    }

    private boolean checkUpper() {
        try {
            upperSocket.sendUrgentData(0);
            return false;
        } catch (IOException e) {
            error(Item.Upper, e, null);
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
                final int headMaxLength = config.getHeadBufferSize();
                try (final ByteTemporaryBuffer byteBuffer = new ByteTemporaryBuffer(localInputStream, SNISocket.this::checkLocal, headMaxLength + 5)) {// 5 bytes for (protocol, version, length) bytes
                    final int protocol = byteBuffer.read8Bit();
                    if (protocol != 0x16)
                        throw new IOException("Unknown Protocol: " + Integer.toHexString(protocol));
                    @SuppressWarnings("unused") final int subVersion = byteBuffer.read8Bit();
                    @SuppressWarnings("unused") final int mainVersion = byteBuffer.read8Bit();
                    final int length = byteBuffer.read16Bit();
                    if (length > headMaxLength) {
                        throw new IOException("Client Hello Too Long: " + length);
                    }
                    final String sniName = SNISocket.this.sniName = parseClientHello(byteBuffer, length);
                    if (sniName == null) {
                        throw new IOException("Unknown sniName");
                    }
                    linkName += " -> " + sniName + ":" + dstPort;
                    upperSocket = new Socket(proxy);
                    upperSocket.setSoTimeout(soTimeout);
                    upperSocket.setKeepAlive(false);
                    upperSocket.setTcpNoDelay(true);
                    upperSocket.setSoLinger(true, 0);
                    InetSocketAddress dest = InetSocketAddress.createUnresolved(sniName, dstPort);
                    upperSocket.connect(dest);
                    upperInputStream = upperSocket.getInputStream();
                    upperOutputStream = upperSocket.getOutputStream();
                    byteBuffer.transferBufferToAndClose(upperOutputStream);
                }
                state = State.Normal;
                log(Item.Parse);
                // server.runForwarder(uploader);
                server.runForwarder(downloader);
                uploader.run();
            } catch (IOException e) {
                error(Item.Parse, e, null);
            }
        }
    }

    public class Uploader implements Forwarder {
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
                            log(Item.Local, Direction.Up, ForwarderState.Running);
                        stateUpload = ForwarderState.Running;
                        try {
                            upperOutputStream.write(buffer, 0, len);
                            upperOutputStream.flush();
                        } catch (IOException e) {
                            error(Item.Upper, e, Direction.Up);
                            return;
                        }
                    }
                } catch (SocketTimeoutException e) {
                    if (checkLocal())
                        return;
                    if (stateUpload == ForwarderState.Running)
                        log(Item.Local, Direction.Up, ForwarderState.Idle);
                    stateUpload = ForwarderState.Idle;
                } catch (IOException e) {
                    error(Item.Local, e, Direction.Up);
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
                            log(Item.Upper, Direction.Down, ForwarderState.Running);
                        stateDownload = ForwarderState.Running;
                        try {
                            localOutputStream.write(buffer, 0, len);
                            localOutputStream.flush();
                        } catch (IOException e) {
                            error(Item.Local, e, Direction.Down);
                            return;
                        }
                    }
                } catch (SocketTimeoutException e) {
                    if (checkUpper())
                        return;
                    if (stateDownload == ForwarderState.Running)
                        log(Item.Upper, Direction.Down, ForwarderState.Idle);
                    stateDownload = ForwarderState.Idle;
                } catch (IOException e) {
                    error(Item.Upper, e, Direction.Down);
                    return;
                }
            }
        }
    }
}
