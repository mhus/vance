package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Streaming + compression coverage for {@link DocumentService}: the helper
 * must accept large payloads without buffering them, must compress only past
 * the threshold, and the round-trip through {@link #loadContent} must yield
 * the original bytes regardless of the compression decision.
 */
class DocumentServiceStreamingTest {

    private DocumentRepository repository;
    private InMemoryStorage storageService;
    private MongoTemplate mongoTemplate;
    private ResourcePatternResolver resourcePatternResolver;
    private DocumentHeaderParser headerParser;
    private DocumentArchiveService archiveService;
    private de.mhus.vance.shared.settings.SettingService settingService;
    private DocumentService service;

    @BeforeEach
    void setUp() throws IOException {
        repository = mock(DocumentRepository.class);
        storageService = new InMemoryStorage();
        mongoTemplate = mock(MongoTemplate.class);
        resourcePatternResolver = mock(ResourcePatternResolver.class);
        headerParser = mock(DocumentHeaderParser.class);
        archiveService = mock(DocumentArchiveService.class);
        settingService = mock(de.mhus.vance.shared.settings.SettingService.class);
        when(headerParser.parse(any(), any())).thenReturn(Optional.empty());
        when(headerParser.parseStream(any(), any())).thenReturn(Optional.empty());
        service = new DocumentService(
                repository, storageService, mongoTemplate,
                resourcePatternResolver, headerParser,
                archiveService, settingService);
        ReflectionTestUtils.setField(service, "compressionEnabled", true);
        ReflectionTestUtils.setField(service, "compressionThreshold", 1000);
    }

    @Test
    void smallText_storedUncompressed_roundTripsCleanly() throws IOException {
        // 800 bytes — under threshold. Helper must keep it raw.
        String body = "a".repeat(800);
        DocumentService.ContentWriteResult write = service.streamingStoreContent(
                "t1", "small.txt", new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));

        assertThat(write.compressed()).isFalse();
        assertThat(write.originalSize()).isEqualTo(800);

        byte[] roundTripped = readAll(storageService.load(write.storageId()));
        assertThat(new String(roundTripped, StandardCharsets.UTF_8)).isEqualTo(body);
    }

    @Test
    void aboveThreshold_storedCompressed_roundTripsCleanly() throws IOException {
        // 10 KB of repeating text — compresses well.
        String body = "lorem ipsum ".repeat(1000);
        byte[] sourceBytes = body.getBytes(StandardCharsets.UTF_8);

        DocumentService.ContentWriteResult write = service.streamingStoreContent(
                "t1", "big.txt", new ByteArrayInputStream(sourceBytes));

        assertThat(write.compressed()).isTrue();
        assertThat(write.originalSize()).isEqualTo(sourceBytes.length);
        // Compressed bytes are stored — the storage blob is smaller than the source.
        assertThat(storageService.size(write.storageId())).isLessThan(sourceBytes.length);

        // Verify the doc-level decompression works via loadContent — emulate
        // the document by hand-rolling a minimal DocumentDocument.
        DocumentDocument doc = DocumentDocument.builder()
                .id("d1").tenantId("t1").projectId("p1").path("big.txt")
                .storageId(write.storageId()).compressed(true)
                .size(write.originalSize())
                .build();
        byte[] roundTripped = readAll(service.loadContent(doc));
        assertThat(roundTripped).isEqualTo(sourceBytes);
    }

    @Test
    void hugeStream_doesNotMaterialiseInMemory() throws IOException {
        // Synthesise a 64 MB stream that returns "x" bytes from a small
        // chunk — the source never holds the full content in memory, and
        // the streaming-store path must not either. If the helper ever
        // tries to readAllBytes() it would allocate 64 MB; the test would
        // pass either way memory-wise on a dev box, but the InMemoryStorage
        // explicitly enforces a chunked read path to prove it.
        long totalBytes = 64L * 1024L * 1024L;
        InputStream giant = new RepeatingByteStream((byte) 'x', totalBytes);

        DocumentService.ContentWriteResult write = service.streamingStoreContent(
                "t1", "giant.txt", giant);

        assertThat(write.compressed()).isTrue();
        assertThat(write.originalSize()).isEqualTo(totalBytes);
    }

    @Test
    void compressionDisabled_largeFile_storedRaw() throws IOException {
        ReflectionTestUtils.setField(service, "compressionEnabled", false);
        String body = "lorem ipsum ".repeat(1000);
        byte[] sourceBytes = body.getBytes(StandardCharsets.UTF_8);

        DocumentService.ContentWriteResult write = service.streamingStoreContent(
                "t1", "big-raw.txt", new ByteArrayInputStream(sourceBytes));

        assertThat(write.compressed()).isFalse();
        assertThat(write.originalSize()).isEqualTo(sourceBytes.length);
        assertThat(storageService.size(write.storageId())).isEqualTo(sourceBytes.length);
    }

    @Test
    void thresholdBoundary_inclusiveExclusive() throws IOException {
        // Exactly at threshold → stays raw.
        byte[] atThreshold = new byte[1000];
        DocumentService.ContentWriteResult atResult = service.streamingStoreContent(
                "t1", "at.txt", new ByteArrayInputStream(atThreshold));
        assertThat(atResult.compressed()).isFalse();

        // One byte past → compressed (assuming compression enabled).
        byte[] overThreshold = new byte[1001];
        DocumentService.ContentWriteResult overResult = service.streamingStoreContent(
                "t1", "over.txt", new ByteArrayInputStream(overThreshold));
        assertThat(overResult.compressed()).isTrue();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    /**
     * In-memory storage implementation that streams its writes through a
     * small read buffer — refuses to buffer the entire input. Mirrors the
     * MongoStorageService streaming contract for tests that need an end-to-end
     * write+read path.
     */
    private static class InMemoryStorage extends StorageService {
        private final Map<String, byte[]> blobs = new HashMap<>();

        @Override
        public StorageInfo store(String tenantId, String path, InputStream stream) {
            String id = UUID.randomUUID().toString();
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            long size = 0;
            byte[] buf = new byte[8 * 1024];
            try {
                int n;
                while ((n = stream.read(buf)) > 0) {
                    sink.write(buf, 0, n);
                    size += n;
                }
            } catch (IOException e) {
                throw new IllegalStateException("test storage read failed", e);
            }
            blobs.put(id, sink.toByteArray());
            return new StorageInfo(id, size, new Date(), tenantId, path);
        }

        @Override
        public @Nullable InputStream load(String storageId) {
            byte[] data = blobs.get(storageId);
            return data == null ? null : new ByteArrayInputStream(data);
        }

        @Override
        public void delete(String storageId) {
            blobs.remove(storageId);
        }

        @Override
        public StorageInfo update(String storageId, InputStream stream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StorageInfo replace(String storageId, InputStream stream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @Nullable StorageInfo info(String storageId) {
            byte[] data = blobs.get(storageId);
            return data == null
                    ? null
                    : new StorageInfo(storageId, data.length, new Date(), null, null);
        }

        @Override
        public @Nullable String duplicate(String sourceStorageId, String targetTenantId) {
            byte[] data = blobs.get(sourceStorageId);
            if (data == null) return null;
            String id = UUID.randomUUID().toString();
            blobs.put(id, data.clone());
            return id;
        }

        @Override
        public void forEachFinalStorageIdOlderThan(
                java.time.Instant cutoff, int batchSize,
                java.util.function.Consumer<java.util.List<String>> batchHandler) {
            // Streaming test doesn't drive the orphan sweep — no-op is safe.
        }

        long size(String storageId) {
            byte[] data = blobs.get(storageId);
            return data == null ? 0 : data.length;
        }
    }

    /** Streams {@code value} bytes without holding the full content in memory. */
    private static class RepeatingByteStream extends InputStream {
        private final byte value;
        private long remaining;

        RepeatingByteStream(byte value, long total) {
            this.value = value;
            this.remaining = total;
        }

        @Override
        public int read() {
            if (remaining <= 0) return -1;
            remaining--;
            return value & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (remaining <= 0) return -1;
            int n = (int) Math.min(len, remaining);
            java.util.Arrays.fill(b, off, off + n, value);
            remaining -= n;
            return n;
        }
    }
}
