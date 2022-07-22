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
        Connect,
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

    @NotNull
    private static String parseClientHello(final ByteTemporaryBuffer byteBuffer, final int handshakeLength) throws IOException {
        final int handshakeType = byteBuffer.read8Bit();
        if (handshakeType != 0x01)
            throw new SNIException(String.format("Handshake (type = 0x%02x) is not ClientHello, expect 0x01", handshakeType));
        final int clientHelloLength = byteBuffer.read24Bit();
        if (handshakeLength != clientHelloLength + 4)
            throw new SNIException(String.format("Handshake length (%d) not match ClientHello length (%d) + 4", handshakeLength, clientHelloLength));
        final int clientHelloStartIndex = byteBuffer.getReadIndex();
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

        final int extensionsLength = byteBuffer.read16Bit();
        if (byteBuffer.getReadIndex() - clientHelloStartIndex + extensionsLength > clientHelloLength) {
            throw new SNIException("Extensions out of bounds");
        }
        final int extensionsStartIndex = byteBuffer.getReadIndex();
        if (extensionsLength > 0) {
            while (byteBuffer.getReadIndex() - extensionsStartIndex < extensionsLength) {
                final int extensionType = byteBuffer.read16Bit();
                final int extensionLength = byteBuffer.read16Bit();
                if (byteBuffer.getReadIndex() - extensionsStartIndex + extensionLength > extensionsLength) {
                    throw new SNIException("Extension out of bounds");
                }
                if (extensionType == 0) {// ServerName Extension
                    final int serverNameListLength = byteBuffer.read16Bit();
                    if (extensionLength != serverNameListLength + 2) {
                        throw new SNIException(String.format("Extension length (%d) not match ServerNameList length (%d) + 2", extensionLength, serverNameListLength));
                    }
                    final int serverNameStartIndex = byteBuffer.getReadIndex();
                    while (byteBuffer.getReadIndex() - serverNameStartIndex < serverNameListLength) {
                        final int serverNameType = byteBuffer.read8Bit();
                        final int serverNameLength = byteBuffer.read16Bit();
                        if (byteBuffer.getReadIndex() - serverNameStartIndex + serverNameLength > serverNameListLength) {
                            throw new SNIException("ServerName out of bounds");
                        }
                        if (serverNameType == 0 && serverNameLength > 0) {
                            return byteBuffer.readString(serverNameLength);
                        } else {
                            // skip other type ServerName
                            byteBuffer.skipLength(serverNameLength);
                        }
                    }
                } else {
                    // skip other type Extension
                    byteBuffer.skipLength(extensionLength);
                }
            }
        }
        throw new SNIException("ServerName not found in ClientHello");
    }

    private synchronized void error(@NotNull final Item item, @NotNull final IOException e, @Nullable final Direction direction) {
        if (state == State.Closed)
            return;
        state = State.Error;
        System.out.format("connections count = %d %s %s %s %s %s: %s\n", server.getConnectionNum(), id, linkName, item, Direction.toString(direction), e.getClass().getName(), e.getMessage());
        if (!(e instanceof SocketException || e instanceof SocketTimeoutException || e instanceof SNIException))
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
            final int headMaxLength = config.getHeadBufferSize();
            try (final ByteTemporaryBuffer byteBuffer = new ByteTemporaryBuffer(localInputStream, SNISocket.this::checkLocal, headMaxLength + 5)) {// 5 bytes for (protocol, version, length) bytes
                final int protocol = byteBuffer.read8Bit();
                if (protocol != 0x16)
                    throw new SNIException(String.format("First byte is 0x%02x, not a TLS Handshake, ", protocol));
                @SuppressWarnings("unused") final int subVersion = byteBuffer.read8Bit();
                @SuppressWarnings("unused") final int mainVersion = byteBuffer.read8Bit();
                final int handshakeLength = byteBuffer.read16Bit();
                if (handshakeLength > headMaxLength) {
                    throw new SNIException(String.format("Handshake too long: %d, buffer max size is %d", handshakeLength, headMaxLength));
                }
                final String sniName = SNISocket.this.sniName = parseClientHello(byteBuffer, handshakeLength);
                linkName += " -> " + sniName + ":" + dstPort;
                log(Item.Parse);
                try {
                    upperSocket = new Socket(proxy);
                    upperSocket.setSoTimeout(soTimeout);
                    upperSocket.setKeepAlive(false);
                    upperSocket.setTcpNoDelay(true);
                    upperSocket.setSoLinger(true, 0);
                    final InetSocketAddress dest = InetSocketAddress.createUnresolved(sniName, dstPort);
                    upperSocket.connect(dest);
                    upperInputStream = upperSocket.getInputStream();
                    upperOutputStream = upperSocket.getOutputStream();
                    byteBuffer.transferBufferToAndClose(upperOutputStream);
                    log(Item.Connect);
                } catch (IOException e) {
                    error(Item.Connect, e, null);
                    return;
                }
            } catch (IOException e) {
                error(Item.Parse, e, null);
                return;
            }
            state = State.Normal;
            // server.runForwarder(uploader);
            server.runForwarder(downloader);
            uploader.run();
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
