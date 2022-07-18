package cc.nium.sni.io;

import cc.nium.sni.annotation.NotNull;
import cc.nium.sni.annotation.Nullable;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public final class ByteTemporaryBuffer implements Closeable {

    @NotNull
    private final byte[] buffer;
    @Nullable
    private InputStream upInputStream;
    private int count = 0;
    private int readIndex = 0;

    public ByteTemporaryBuffer(@NotNull final InputStream upInputStream, final int capacity) {
        this.upInputStream = upInputStream;
        this.buffer = new byte[capacity];
    }

    public ByteTemporaryBuffer(@NotNull final InputStream upInputStream) {
        this(upInputStream, 8192);
    }

    private synchronized void ensureContent(final int need) throws IOException {
        if (upInputStream == null)
            throw new IOException("buffer is closed");
        final int totalNeedLength = readIndex + need;
        if (totalNeedLength > buffer.length)
            throw new IOException("buffer is fulled");
        while (count < totalNeedLength) {
            final int available = upInputStream.available();
            if (available < 0)
                throw new EOFException();
            final int readCount = Math.max(buffer.length - count, available);
            final int len = upInputStream.read(buffer, count, readCount);
            count += len;
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
        upInputStream = null;
    }
}
