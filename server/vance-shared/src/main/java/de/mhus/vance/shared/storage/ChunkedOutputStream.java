package de.mhus.vance.shared.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * {@link OutputStream} adapter that flushes data into MongoDB one chunk at a
 * time. Memory footprint is {@code O(chunkSize)} regardless of total size.
 * Package-private — only {@link MongoStorageService} instantiates this.
 */
@Slf4j
class ChunkedOutputStream extends OutputStream {

    private final StorageDataRepository repository;
    private final String uuid;
    private final @Nullable String path;
    private final int chunkSize;
    private final Date createdAt;
    private final @Nullable String tenantId;

    private byte @Nullable [] buffer;
    private int bufferPosition = 0;
    private int chunkIndex = 0;
    private long totalBytesWritten = 0;
    private boolean closed = false;
    private @Nullable StorageData lastChunk;

    ChunkedOutputStream(
            StorageDataRepository repository,
            String uuid,
            @Nullable String tenantId,
            @Nullable String path,
            int chunkSize,
            Date createdAt) {
        this.repository = repository;
        this.uuid = uuid;
        this.tenantId = tenantId;
        this.path = path;
        this.chunkSize = chunkSize;
        this.createdAt = createdAt;
        this.buffer = new byte[chunkSize];
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        byte[] buf = requireBuffer();
        buf[bufferPosition++] = (byte) b;
        totalBytesWritten++;
        if (bufferPosition >= chunkSize) {
            flushChunk(false);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if (off < 0 || len < 0 || off + len > b.length) {
            throw new IndexOutOfBoundsException("Invalid offset or length");
        }
        int remaining = len;
        int offset = off;
        while (remaining > 0) {
            byte[] buf = requireBuffer();
            int spaceInBuffer = chunkSize - bufferPosition;
            int bytesToWrite = Math.min(remaining, spaceInBuffer);
            System.arraycopy(b, offset, buf, bufferPosition, bytesToWrite);
            bufferPosition += bytesToWrite;
            totalBytesWritten += bytesToWrite;
            offset += bytesToWrite;
            remaining -= bytesToWrite;
            if (bufferPosition >= chunkSize) {
                flushChunk(false);
            }
        }
    }

    @Override
    public void flush() throws IOException {
        // Intentionally empty — partial chunks are only flushed on close() to
        // preserve the „one full chunk per document" invariant (see nimbus original).
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        try {
            if (bufferPosition > 0) {
                flushChunk(true);
            } else if (lastChunk != null) {
                // Re-save the last chunk as final so it carries the total size.
                StorageData finalChunk = StorageData.builder()
                        .uuid(uuid)
                        .path(path)
                        .index(lastChunk.getIndex())
                        .data(lastChunk.getData())
                        .isFinal(true)
                        .size(totalBytesWritten)
                        .createdAt(createdAt)
                        .tenantId(tenantId)
                        .build();
                repository.save(finalChunk);
                log.trace("Updated last chunk as final: uuid={} index={}", uuid, lastChunk.getIndex());
            } else {
                flushChunk(true);
            }
            log.debug("ChunkedOutputStream closed: uuid={} chunks={} totalBytes={}",
                    uuid, chunkIndex, totalBytesWritten);
        } finally {
            closed = true;
            buffer = null;
        }
    }

    long getTotalBytesWritten() {
        return totalBytesWritten;
    }

    private void flushChunk(boolean isFinal) throws IOException {
        if (bufferPosition == 0 && !isFinal) {
            return;
        }
        byte[] buf = requireBuffer();
        byte[] chunkData = Arrays.copyOf(buf, bufferPosition);
        StorageData chunk = StorageData.builder()
                .uuid(uuid)
                .path(path)
                .index(chunkIndex)
                .data(chunkData)
                .isFinal(isFinal)
                .size(isFinal ? totalBytesWritten : 0)
                .createdAt(createdAt)
                .tenantId(tenantId)
                .build();
        try {
            repository.save(chunk);
            lastChunk = chunk;
            log.trace("Saved chunk: uuid={} index={} size={} final={}",
                    uuid, chunkIndex, chunkData.length, isFinal);
            chunkIndex++;
            bufferPosition = 0;
        } catch (Exception e) {
            log.error("Failed to save chunk: uuid={} index={}", uuid, chunkIndex, e);
            throw new IOException("Failed to save chunk to MongoDB", e);
        }
    }

    private byte[] requireBuffer() throws IOException {
        byte[] buf = buffer;
        if (buf == null) {
            throw new IOException("Stream is closed");
        }
        return buf;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
    }
}
