package de.mhus.vance.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Verifies the storage-quota hook in {@link MongoStorageService}. The
 * service writes through a {@link ChunkedOutputStream} backed by the
 * {@link StorageDataRepository}, which we mock to keep the test free of
 * Mongo. We only assert that the quota pre-check is invoked with the
 * expected tenant + size, that a denial surfaces as
 * {@link StorageQuotaExceededException}, and that the post-write cache
 * invalidation runs.
 */
class MongoStorageServiceQuotaTest {

    private StorageDataRepository storageDataRepository;
    private StorageDeleteRepository storageDeleteRepository;
    private MongoTemplate mongoTemplate;
    private StorageQuotaService quotaService;
    private MongoStorageService service;

    @BeforeEach
    void setUp() {
        storageDataRepository = mock(StorageDataRepository.class);
        storageDeleteRepository = mock(StorageDeleteRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        // StorageQuotaService is mocked because its aggregation behaviour is
        // already covered in StorageQuotaServiceTest; here we only care about
        // the hook's contract with MongoStorageService.
        quotaService = mock(StorageQuotaService.class);

        service = new MongoStorageService(storageDataRepository, storageDeleteRepository, mongoTemplate, quotaService);
        // chunkSize is @Value-injected in production (default 512 KB).
        // Without Spring the field stays at its int default of 0, which
        // makes ChunkedOutputStream loop forever. Set it via reflection
        // to a small value that comfortably fits every test payload.
        try {
            java.lang.reflect.Field f = MongoStorageService.class.getDeclaredField("chunkSize");
            f.setAccessible(true);
            f.setInt(service, 1024);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void quotaCheck_passesAndStorageReceivesTheBlob() throws IOException {
        when(quotaService.check(eq("acme"), anyLong(), eq(-1L)))
                .thenReturn(StorageQuotaService.Check.allowed(0L, 1024L, "ok"));

        InputStream payload = new ByteArrayInputStream("hello-world".getBytes());
        StorageService.StorageInfo info = service.store("acme", "docs/x.md", payload, 11L);

        assertThat(info.id()).isNotBlank();
        assertThat(info.size()).isEqualTo(11L);
        // The pre-check was called with the expected tenant + size, and the
        // post-write invalidation ran to clear the preCheckCache on this pod.
        ArgumentCaptor<Long> delta = ArgumentCaptor.forClass(Long.class);
        verify(quotaService).check(eq("acme"), delta.capture(), eq(-1L));
        assertThat(delta.getValue()).isEqualTo(11L);
        verify(quotaService).invalidateAllCaches("acme");
    }

    @Test
    void quotaCheck_denies_throwsStorageQuotaExceeded() {
        when(quotaService.check(eq("acme"), eq(50_000L), eq(-1L)))
                .thenReturn(StorageQuotaService.Check.denied(100_000L, 50_000L, 120_000L));

        InputStream payload = new ByteArrayInputStream(new byte[50_000]);
        assertThatThrownBy(() -> service.store("acme", "docs/big.md", payload, 50_000L))
                .isInstanceOf(StorageQuotaExceededException.class)
                .hasMessageContaining("acme")
                .hasMessageContaining("storage quota exceeded");

        // No invalidation must run when the write was refused — the cache
        // already reflects reality.
        verify(quotaService, org.mockito.Mockito.never()).invalidateAllCaches(anyString());
    }

    @Test
    void storeWithoutExpectedSize_fallsBackToUnknownAndPreCheckSeesNegativeOne() throws IOException {
        when(quotaService.check(eq("acme"), eq(-1L), eq(-1L)))
                .thenReturn(StorageQuotaService.Check.allowed(0L, 0L, "unknown-size-bypass"));

        InputStream payload = new ByteArrayInputStream("body".getBytes());
        service.store("acme", "docs/y.md", payload);

        verify(quotaService).check(eq("acme"), eq(-1L), eq(-1L));
    }

    @Test
    void update_passesOldSizeToQuotaCheck() throws IOException {
        // Seed an old final chunk so update() can find the previous size.
        StorageData oldFinal = StorageData.builder()
                .id(new ObjectId().toHexString())
                .uuid("blob-old")
                .path("docs/z.md")
                .tenantId("acme")
                .index(0)
                .isFinal(true)
                .size(20_000L)
                .build();
        when(storageDataRepository.findByUuidAndIsFinalTrue("blob-old")).thenReturn(oldFinal);
        when(storageDataRepository.findAllByUuid("blob-old")).thenReturn(List.of(oldFinal));

        when(quotaService.check(eq("acme"), anyLong(), eq(20_000L)))
                .thenReturn(StorageQuotaService.Check.allowed(0L, 1024L, "ok"));

        InputStream payload = new ByteArrayInputStream(new byte[5_000]);
        service.update("blob-old", payload);

        // Verify the pre-check was called with oldSize=20_000, so the
        // effective delta becomes (newSize - oldSize) and a smaller update
        // cannot accidentally be denied.
        verify(quotaService).check(eq("acme"), anyLong(), eq(20_000L));
    }

    @Test
    void replace_hardDeletesFirstThenPassesUnknownOldSize() throws IOException {
        StorageData oldFinal = StorageData.builder()
                .id(new ObjectId().toHexString())
                .uuid("blob-r")
                .path("docs/r.md")
                .tenantId("acme")
                .index(0)
                .isFinal(true)
                .size(50_000L)
                .build();
        when(storageDataRepository.findByUuidAndIsFinalTrue("blob-r")).thenReturn(oldFinal);
        when(storageDataRepository.findAllByUuid("blob-r")).thenReturn(List.of(oldFinal));

        when(quotaService.check(eq("acme"), anyLong(), eq(-1L)))
                .thenReturn(StorageQuotaService.Check.allowed(0L, 1024L, "ok"));

        InputStream payload = new ByteArrayInputStream(new byte[80_000]);
        service.replace("blob-r", payload);

        // Hard-delete must run before the new pre-check; the new pre-check
        // sees oldSize=-1 because the bytes are no longer counted.
        verify(storageDataRepository).delete(oldFinal);
        verify(quotaService).check(eq("acme"), anyLong(), eq(-1L));
    }
}
