package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.storage.StorageService;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Trigger-logic for {@link DocumentService#update}'s archive call:
 * archiving fires only when the cascade is on, content changed, and the
 * min-version-interval elapsed. {@link DocumentService#delete} drops the
 * lineage along with the live row.
 */
class DocumentServiceArchiveTriggerTest {

    private DocumentRepository repository;
    private StorageService storageService;
    private MongoTemplate mongoTemplate;
    private ResourcePatternResolver resourcePatternResolver;
    private DocumentHeaderParser headerParser;
    private DocumentArchiveService archiveService;
    private SettingService settingService;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        storageService = mock(StorageService.class);
        mongoTemplate = mock(MongoTemplate.class);
        resourcePatternResolver = mock(ResourcePatternResolver.class);
        headerParser = mock(DocumentHeaderParser.class);
        archiveService = mock(DocumentArchiveService.class);
        settingService = mock(SettingService.class);
        when(headerParser.parse(any(), any())).thenReturn(Optional.empty());
        try {
            when(headerParser.parseStream(any(), any())).thenReturn(Optional.empty());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e); // mock setup, never throws
        }
        when(storageService.store(any(), any(), any())).thenAnswer(inv -> {
            java.io.InputStream stream = inv.getArgument(2);
            long size = stream.readAllBytes().length;
            return new StorageService.StorageInfo(
                    "blob-" + java.util.UUID.randomUUID(), size, new Date(), null, null);
        });
        service = new DocumentService(
                repository, storageService, mongoTemplate,
                resourcePatternResolver, headerParser,
                archiveService, settingService, DocTestSupport.permissionProvider());
        ReflectionTestUtils.setField(service, "inlineThreshold", 40960);
        ReflectionTestUtils.setField(service, "compressionEnabled", false);
        ReflectionTestUtils.setField(service, "compressionThreshold", 1000);
        ReflectionTestUtils.setField(service, "archiveEnabledDefault", true);
        ReflectionTestUtils.setField(service, "archiveMinIntervalSecondsDefault", 600L);
        when(settingService.getBooleanValueCascade(
                anyString(), anyString(), any(), eq(DocumentService.SETTING_ARCHIVE_ENABLED), anyBoolean()))
                .thenReturn(true);
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static boolean anyBoolean() { return org.mockito.ArgumentMatchers.anyBoolean(); }

    // ──── update() — trigger logic ─────────────────────────────────────

    @Test
    void update_contentChange_pastInterval_archivesBeforeOverwrite() {
        DocumentDocument doc = freshDoc("hello", Instant.now().minusSeconds(3600));
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        service.update("doc-1", null, null, "world", null, de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        verify(archiveService, times(1)).archiveCurrent(any(DocumentDocument.class));
    }

    @Test
    void update_contentChange_withinInterval_skipsArchive() {
        DocumentDocument doc = freshDoc("hello", Instant.now().minusSeconds(30));
        doc.setLastArchivedAt(Instant.now().minusSeconds(60));
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        service.update("doc-1", null, null, "world", null, de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        verify(archiveService, never()).archiveCurrent(any(DocumentDocument.class));
    }

    @Test
    void update_sameContent_skipsArchive() {
        DocumentDocument doc = freshDoc("hello", Instant.now().minusSeconds(3600));
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        service.update("doc-1", null, null, "hello", null, de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        verify(archiveService, never()).archiveCurrent(any(DocumentDocument.class));
    }

    @Test
    void update_cascadeDisabled_skipsArchive() {
        DocumentDocument doc = freshDoc("hello", Instant.now().minusSeconds(3600));
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));
        when(settingService.getBooleanValueCascade(
                anyString(), anyString(), any(),
                eq(DocumentService.SETTING_ARCHIVE_ENABLED), anyBoolean()))
                .thenReturn(false);

        service.update("doc-1", null, null, "world", null, de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        verify(archiveService, never()).archiveCurrent(any(DocumentDocument.class));
    }

    @Test
    void update_operatorKillSwitch_skipsArchive() {
        DocumentDocument doc = freshDoc("hello", Instant.now().minusSeconds(3600));
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));
        ReflectionTestUtils.setField(service, "archiveEnabledDefault", false);

        service.update("doc-1", null, null, "world", null, de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        verify(archiveService, never()).archiveCurrent(any(DocumentDocument.class));
        verify(settingService, never()).getBooleanValueCascade(
                anyString(), anyString(), any(), anyString(), anyBoolean());
    }

    @Test
    void update_titleOnlyChange_skipsArchive() {
        // No newInlineText → no content change → no archive trigger.
        DocumentDocument doc = freshDoc("hello", Instant.now().minusSeconds(3600));
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        service.update("doc-1", "New Title", null, null, null, de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        verify(archiveService, never()).archiveCurrent(any(DocumentDocument.class));
    }

    // ──── delete() — wipes lineage ─────────────────────────────────────

    @Test
    void delete_dropsArchiveLineage() {
        DocumentDocument doc = freshDoc("hello", Instant.now().minusSeconds(60));
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        service.delete("doc-1", de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        verify(archiveService).deleteAllForLineage("t1", "p1", doc.getLineageId());
        verify(repository).delete(doc);
    }

    @Test
    void delete_archiveFailure_doesNotPreventLiveDelete() {
        DocumentDocument doc = freshDoc("hello", Instant.now().minusSeconds(60));
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));
        org.mockito.Mockito.doThrow(new RuntimeException("mongo down"))
                .when(archiveService).deleteAllForLineage(any(), any(), any());

        // Must not throw — live delete already happened.
        service.delete("doc-1", de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        verify(repository).delete(doc);
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private DocumentDocument freshDoc(String body, Instant createdAt) {
        when(storageService.load("blob-baseline")).thenAnswer(inv ->
                new java.io.ByteArrayInputStream(body.getBytes()));
        DocumentDocument doc = DocumentDocument.builder()
                .tenantId("t1")
                .projectId("p1")
                .path("notes/a.md")
                .name("a.md")
                .mimeType("text/markdown")
                .storageId("blob-baseline")
                .size(body.getBytes().length)
                .lineageId("lin-1")
                .build();
        doc.setId("doc-1");
        doc.setCreatedAt(createdAt);
        return doc;
    }
}
