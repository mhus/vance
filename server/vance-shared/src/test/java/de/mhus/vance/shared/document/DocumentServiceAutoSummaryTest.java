package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Auto-summary fields on {@link DocumentService}: default {@code autoSummary}
 * at create-time, {@code summaryDirty} mark on content-change, and the
 * scheduler-facing claim / write / release trio.
 */
class DocumentServiceAutoSummaryTest {

    private DocumentRepository repository;
    private StorageService storageService;
    private MongoTemplate mongoTemplate;
    private ResourcePatternResolver resourcePatternResolver;
    private DocumentHeaderParser headerParser;
    private DocumentArchiveService archiveService;
    private de.mhus.vance.shared.settings.SettingService settingService;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        storageService = mock(StorageService.class);
        mongoTemplate = mock(MongoTemplate.class);
        resourcePatternResolver = mock(ResourcePatternResolver.class);
        headerParser = mock(DocumentHeaderParser.class);
        archiveService = mock(DocumentArchiveService.class);
        settingService = mock(de.mhus.vance.shared.settings.SettingService.class);
        when(headerParser.parse(any(), any())).thenReturn(Optional.empty());
        try {
            when(headerParser.parseStream(any(), any())).thenReturn(Optional.empty());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e); // mock setup, never throws
        }
        // streamingStoreContent() always hits storageService.store(); pretend
        // every blob lands at a deterministic id.
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
    }

    // ──── create() — autoSummary default ────────────────────────────────

    @Test
    void create_markdown_setsAutoSummaryTrue() {
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        DocumentDocument saved = service.create(
                "t1", "p1", "notes/a.md", null, null, "text/markdown",
                new ByteArrayInputStream("hello".getBytes()), "alice",
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);
        assertThat(saved.isAutoSummary()).isTrue();
        assertThat(saved.isSummaryDirty()).isFalse();
    }

    @Test
    void create_plainText_setsAutoSummaryTrue() {
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        DocumentDocument saved = service.create(
                "t1", "p1", "notes/a.txt", null, null, "text/plain",
                new ByteArrayInputStream("hello".getBytes()), "alice",
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);
        assertThat(saved.isAutoSummary()).isTrue();
    }

    @Test
    void create_markdownWithCharsetSuffix_setsAutoSummaryTrue() {
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        DocumentDocument saved = service.create(
                "t1", "p1", "notes/a.md", null, null, "text/markdown; charset=utf-8",
                new ByteArrayInputStream("hello".getBytes()), "alice",
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);
        assertThat(saved.isAutoSummary()).isTrue();
    }

    @Test
    void create_jsonCodeFile_doesNotSetAutoSummary() {
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        DocumentDocument saved = service.create(
                "t1", "p1", "config/a.json", null, null, "application/json",
                new ByteArrayInputStream("{}".getBytes()), "alice",
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);
        assertThat(saved.isAutoSummary()).isFalse();
    }

    @Test
    void create_pdf_doesNotSetAutoSummary() {
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // setUp() already provides a streaming store stub.
        DocumentDocument saved = service.create(
                "t1", "p1", "docs/a.pdf", null, null, "application/pdf",
                new ByteArrayInputStream("%PDF-".getBytes()), "alice",
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);
        assertThat(saved.isAutoSummary()).isFalse();
    }

    // ──── upsertEphemeralText() — force-disables summary + RAG ──────────

    @Test
    void upsertEphemeralText_create_forcesAutoSummaryOffAndRagDisabled() {
        when(repository.findByTenantIdAndProjectIdAndPath(
                any(), any(), any())).thenReturn(Optional.empty());
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DocumentDocument saved = service.upsertEphemeralText(
                "t1", "p1", "_vance/logs/scheduler/run-1.md",
                "Scheduler run 1", List.of("scheduler-log"),
                "body", "ursascheduler",
                Instant.now().plus(Duration.ofDays(7)),
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        // Markdown would normally default to autoSummary=true; the ephemeral
        // path must override that so the auto-summary scheduler ignores logs.
        assertThat(saved.isAutoSummary()).isFalse();
        assertThat(saved.isSummaryDirty()).isFalse();
        // Explicit ragEnabled=false so the RAG indexer skips logs regardless
        // of path-based eligibility (defensive: avoids surprises if log paths
        // are ever moved under documents/).
        assertThat(saved.getRagEnabled()).isFalse();
        assertThat(saved.isRagDirty()).isFalse();
        assertThat(saved.getExpiresAt()).isNotNull();
    }

    @Test
    void upsertEphemeralText_reupsert_clearsDirtyFlagsFromUpdate() {
        // Existing doc that's already ephemeral-correctly configured.
        DocumentDocument existing = DocumentDocument.builder()
                .id("d1").tenantId("t1").projectId("p1")
                .path("_vance/logs/scheduler/run-1.md")
                .name("run-1.md")
                .mimeType("text/markdown")
                .storageId("blob-old").size(8)
                .autoSummary(false)
                .ragEnabled(false)
                .build();
        when(repository.findByTenantIdAndProjectIdAndPath(
                any(), any(), any())).thenReturn(Optional.of(existing));
        when(repository.findById("d1")).thenReturn(Optional.of(existing));
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(storageService.load("blob-old")).thenReturn(
                new java.io.ByteArrayInputStream("old body".getBytes()));

        DocumentDocument saved = service.upsertEphemeralText(
                "t1", "p1", "_vance/logs/scheduler/run-1.md",
                "Scheduler run 1", List.of("scheduler-log"),
                "new body", "ursascheduler",
                Instant.now().plus(Duration.ofDays(7)),
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        // update() would have flipped summaryDirty + ragDirty to true because
        // the body changed — the ephemeral overlay clears them back to false.
        assertThat(saved.isAutoSummary()).isFalse();
        assertThat(saved.isSummaryDirty()).isFalse();
        assertThat(saved.getRagEnabled()).isFalse();
        assertThat(saved.isRagDirty()).isFalse();
    }

    // ──── update() — summaryDirty on content change ─────────────────────

    @Test
    void update_inlineTextChanged_setsSummaryDirty() {
        DocumentDocument existing = DocumentDocument.builder()
                .id("d1").tenantId("t1").projectId("p1")
                .path("a.md").name("a.md")
                .mimeType("text/markdown")
                .storageId("blob-old").size(8)
                .build();
        when(repository.findById("d1")).thenReturn(Optional.of(existing));
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(storageService.load("blob-old")).thenReturn(
                new java.io.ByteArrayInputStream("old body".getBytes()));

        DocumentDocument saved = service.update(
                "d1", null, null, "new body", null,
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        assertThat(saved.isSummaryDirty()).isTrue();
        assertThat(saved.getStorageId()).isNotNull();
        assertThat(saved.getSize()).isEqualTo("new body".length());
    }

    @Test
    void update_inlineTextUnchanged_doesNotSetSummaryDirty() {
        DocumentDocument existing = DocumentDocument.builder()
                .id("d1").tenantId("t1").projectId("p1")
                .path("a.md").name("a.md")
                .mimeType("text/markdown")
                .storageId("blob-body").size(4)
                .build();
        when(repository.findById("d1")).thenReturn(Optional.of(existing));
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(storageService.load("blob-body")).thenReturn(
                new java.io.ByteArrayInputStream("body".getBytes()));

        DocumentDocument saved = service.update(
                "d1", null, null, "body", null,
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        assertThat(saved.isSummaryDirty()).isFalse();
    }

    @Test
    void update_titleOnly_doesNotSetSummaryDirty() {
        DocumentDocument existing = DocumentDocument.builder()
                .id("d1").tenantId("t1").projectId("p1")
                .path("a.md").name("a.md")
                .mimeType("text/markdown")
                .storageId("blob-body").size(4)
                .build();
        when(repository.findById("d1")).thenReturn(Optional.of(existing));
        when(repository.save(any(DocumentDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DocumentDocument saved = service.update(
                "d1", "New Title", null, null, null,
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        assertThat(saved.isSummaryDirty()).isFalse();
        assertThat(saved.getTitle()).isEqualTo("New Title");
    }

    // ──── claimForSummary() ─────────────────────────────────────────────

    @Test
    void claimForSummary_runsStaleRecoveryThenClaimsAvailable() {
        DocumentDocument claimed = DocumentDocument.builder()
                .id("d1").tenantId("t1").projectId("p1")
                .summaryDirty(true).autoSummary(true)
                .claimedBy("pod-a").claimedAt(Instant.now())
                .build();
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(DocumentDocument.class)))
                .thenReturn(claimed)
                .thenReturn(null);

        List<DocumentDocument> result = service.claimForSummary(
                "t1", "p1", "pod-a", 5, Duration.ofMinutes(10));

        assertThat(result).hasSize(1);
        // Stale-recovery updateMulti runs once.
        verify(mongoTemplate).updateMulti(
                any(Query.class), any(Update.class), eq(DocumentDocument.class));
        // Claim loop calls findAndModify until null is returned.
        verify(mongoTemplate, org.mockito.Mockito.times(2)).findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(DocumentDocument.class));
    }

    @Test
    void claimForSummary_returnsEmptyWhenNoDirtyDocs() {
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(DocumentDocument.class)))
                .thenReturn(null);

        List<DocumentDocument> result = service.claimForSummary(
                "t1", "p1", "pod-a", 5, Duration.ofMinutes(10));

        assertThat(result).isEmpty();
    }

    @Test
    void claimForSummary_stopsAtBatchSize() {
        DocumentDocument doc = DocumentDocument.builder().id("d1").build();
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(DocumentDocument.class)))
                .thenReturn(doc);

        List<DocumentDocument> result = service.claimForSummary(
                "t1", "p1", "pod-a", 3, Duration.ofMinutes(10));

        assertThat(result).hasSize(3);
        verify(mongoTemplate, org.mockito.Mockito.times(3)).findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(DocumentDocument.class));
    }

    // ──── writeSummary() ────────────────────────────────────────────────

    @Test
    void writeSummary_clearsSummaryDirtyAndClaimFields() {
        service.writeSummary("d1", "the summary",
                List.of("alpha", "beta"));

        ArgumentCaptor<Update> updateCap = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(
                any(Query.class), updateCap.capture(),
                eq(DocumentDocument.class));

        Document wire = updateCap.getValue().getUpdateObject();
        Document set = (Document) wire.get("$set");
        Document unset = (Document) wire.get("$unset");

        assertThat(set.getString("summary")).isEqualTo("the summary");
        assertThat(set.get("tags")).isEqualTo(List.of("alpha", "beta"));
        assertThat(set.getBoolean("summaryDirty")).isFalse();
        assertThat(set.get("summarizedAt")).isNotNull();
        assertThat(unset.keySet()).containsExactlyInAnyOrder("claimedBy", "claimedAt");
    }

    // ──── releaseClaim() ────────────────────────────────────────────────

    @Test
    void releaseClaim_unsetsClaimFieldsButLeavesSummaryDirty() {
        service.releaseClaim("d1");

        ArgumentCaptor<Update> updateCap = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(
                any(Query.class), updateCap.capture(),
                eq(DocumentDocument.class));

        Document wire = updateCap.getValue().getUpdateObject();
        Document unset = (Document) wire.get("$unset");
        assertThat(unset.keySet()).containsExactlyInAnyOrder("claimedBy", "claimedAt");
        // Crucially: no $set on summaryDirty — the doc stays dirty so
        // the next tick re-tries it.
        assertThat(wire.get("$set")).isNull();
    }
}
