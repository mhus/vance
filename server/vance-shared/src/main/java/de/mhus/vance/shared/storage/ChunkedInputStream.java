package de.mhus.vance.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.IncorrectResultSizeDataAccessException;

/**
 * {@link InputStream} adapter that lazy-loads chunks from MongoDB — only one
 * chunk at a time is in memory. Package-private — only
 * {@link MongoStorageService} instantiates this.
 */
@Slf4j
class ChunkedInputStream extends InputStream {

    private final StorageDataRepository repository;
    private final String uuid;

    private int currentChunkIndex = 0;
    private byte @Nullable [] currentChunkData;
    private int positionInChunk = 0;
    private boolean isEOF = false;
    private boolean closed = false;

    ChunkedInputStream(StorageDataRepository repository, String uuid) {
        this.repository = repository;
        this.uuid = uuid;
        loadNextChunk();
        log.trace("ChunkedInputStream created: uuid={}", uuid);
    }

    @Override
    public int read() throws IOException {
        ensureOpen();
        byte[] data = currentChunkData;
        if (data == null) {
            return -1;
        }
        if (positionInChunk >= data.length) {
            if (isEOF) {
                return -1;
            }
            loadNextChunk();
            data = currentChunkData;
            if (data == null) {
                return -1;
            }
        }
        return data[positionInChunk++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if (off < 0 || len < 0 || off + len > b.length) {
            throw new IndexOutOfBoundsException("Invalid offset or length");
        }
        if (len == 0) {
            return 0;
        }
        byte[] data = currentChunkData;
        if (data == null) {
            return -1;
        }
        int totalRead = 0;
        while (totalRead < len) {
            if (positionInChunk >= data.length) {
                if (isEOF) {
                    break;
                }
                loadNextChunk();
                data = currentChunkData;
                if (data == null) {
                    break;
                }
            }
            int remaining = data.length - positionInChunk;
            int bytesToRead = Math.min(remaining, len - totalRead);
            System.arraycopy(data, positionInChunk, b, off + totalRead, bytesToRead);
            positionInChunk += bytesToRead;
            totalRead += bytesToRead;
        }
        return totalRead > 0 ? totalRead : -1;
    }

    @Override
    public int available() throws IOException {
        ensureOpen();
        byte[] data = currentChunkData;
        return data == null ? 0 : data.length - positionInChunk;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            currentChunkData = null;
            log.trace("ChunkedInputStream closed: uuid={}", uuid);
        }
    }

    private void loadNextChunk() {
        try {
            StorageData chunk;
            try {
                chunk = repository.findByUuidAndIndex(uuid, currentChunkIndex);
            } catch (IncorrectResultSizeDataAccessException e) {
                log.error("Multiple chunks found: uuid={} index={}", uuid, currentChunkIndex, e);
                List<StorageData> chunks = repository.findAllByUuidAndIndex(uuid, currentChunkIndex);
                chunks.sort((a, b) -> {
                    java.util.Date da = a.getCreatedAt();
                    java.util.Date db = b.getCreatedAt();
                    if (da == null && db == null) return 0;
                    if (da == null) return 1;
                    if (db == null) return -1;
                    return db.compareTo(da);
                });
                chunk = chunks.removeLast();
                for (StorageData duplicate : chunks) {
                    log.info("Deleting duplicate chunk id={} createdAt={}",
                            duplicate.getId(), duplicate.getCreatedAt());
                    repository.delete(duplicate);
                }
            }
            if (chunk == null) {
                if (currentChunkIndex == 0) {
                    throw new IllegalStateException("No chunks found for uuid: " + uuid);
                }
                currentChunkData = null;
                isEOF = true;
                log.trace("EOF reached: uuid={} totalChunks={}", uuid, currentChunkIndex);
                return;
            }
            if (chunk.getIndex() != currentChunkIndex) {
                throw new IllegalStateException(
                        "Chunk sequence error: expected index " + currentChunkIndex
                                + " but got " + chunk.getIndex() + " for uuid " + uuid);
            }
            currentChunkData = chunk.getData();
            positionInChunk = 0;
            log.trace("Loaded chunk: uuid={} index={} size={}",
                    uuid, currentChunkIndex,
                    currentChunkData == null ? 0 : currentChunkData.length);
            if (chunk.isFinal()) {
                isEOF = true;
                log.trace("Final chunk loaded: uuid={} totalChunks={}", uuid, currentChunkIndex + 1);
            }
            currentChunkIndex++;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to load chunk: uuid={} index={}", uuid, currentChunkIndex, e);
            throw new IllegalStateException("Failed to load chunk from MongoDB", e);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
    }
}
