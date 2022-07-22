package cc.nium.sni.io;

import cc.nium.sni.annotation.NotNull;
import cc.nium.sni.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@SuppressWarnings({"WeakerAccess"})
public final class ByteTemporaryBuffer implements Closeable {

    @NotNull
    private final byte[] buffer;
    @NotNull
    private final SocketChecker socketChecker;
    @Nullable
    private InputStream inputStream;
    private int count = 0;
    private int readIndex = 0;

    public ByteTemporaryBuffer(@NotNull final InputStream inputStream, @NotNull final SocketChecker socketChecker, final int capacity) {
        this.inputStream = inputStream;
        this.socketChecker = socketChecker;
        this.buffer = new byte[capacity];
    }

    public ByteTemporaryBuffer(@NotNull final InputStream inputStream, @NotNull final SocketChecker socketChecker) {
        this(inputStream, socketChecker, 8192);
    }

    private synchronized void ensureContent(final int needLength) throws IOException {
        if (inputStream == null)
            throw new IOException("Buffer is closed");
        final int totalNeedLength = readIndex + needLength;
        if (totalNeedLength > buffer.length)
            throw new IOException("Buffer is fulled");
        while (count < totalNeedLength) {
            try {
                final int len = inputStream.read(buffer, count, buffer.length - count);
                if (len < 0) {
                    if (socketChecker.check())
                        throw new SocketException("Socket Error");
                } else {
                    count += len;
                }
            } catch (SocketTimeoutException e) {
                if (socketChecker.check())
                    throw new SocketException("Socket Error");
            }
        }
    }

    public int getReadIndex() {
        return readIndex;
    }

    public synchronized int read8Bit() throws IOException {
        ensureContent(1);
        return buffer[readIndex++] & 0xFF;
    }

    public synchronized int read16Bit() throws IOException {
        ensureContent(2);
        return ((buffer[readIndex++] & 0xFF) << 8) | (buffer[readIndex++] & 0xFF);
    }

    public synchronized int read24Bit() throws IOException {
        ensureContent(3);
        return ((buffer[readIndex++] & 0xFF) << 16) | ((buffer[readIndex++] & 0xFF) << 8) | (buffer[readIndex++] & 0xFF);
    }

    public synchronized int read32Bit() throws IOException {
        ensureContent(4);
        return ((buffer[readIndex++] & 0xFF) << 24) | ((buffer[readIndex++] & 0xFF) << 16) | ((buffer[readIndex++] & 0xFF) << 8) | (buffer[readIndex++] & 0xFF);
    }

    public synchronized String readString(final int length, @NotNull final Charset charset) throws IOException {
        ensureContent(length);
        final String s = new String(buffer, readIndex, length, charset);
        readIndex += length;
        return s;
    }

    public synchronized String readString(final int length) throws IOException {
        return readString(length, StandardCharsets.UTF_8);
    }

    public synchronized void skipLength(final int length) throws IOException {
        if (length > 0) {
            ensureContent(length);
            readIndex += length;
        }
    }

    public synchronized void transferBufferToAndClose(@NotNull final OutputStream out) throws IOException {
        close();
        out.write(buffer, 0, count);
        out.flush();
    }

    @Override
    public synchronized void close() {
        inputStream = null;
    }
}
