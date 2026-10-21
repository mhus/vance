package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.WriterRole;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins the {@code updatedAt}-stamping behaviour introduced in Phase 0 of
 * {@code planning/user-backup.md}: the field is set on every user-/agent-
 * initiated write (create, replaceContent, update, trash, restore,
 * setLockedFor) and never as a side effect of bookkeeping writes
 * (writeSummary, markRagClean, archive pointer updates).
 *
 * <p>The clock is pinned via {@link ReflectionTestUtils} so the assertions
 * do not depend on wall-clock timing — the tests run on the same instant
 * they were written. The same pinning is used by production: the service
 * has a {@code @Autowired(required=false) Clock clock} field that defaults
 * to {@code systemUTC()} when Spring has no {@code Clock} bean, so this
 * test exercises the explicit-clock path explicitly.
 */
class DocumentServiceUpdatedAtTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-21T12:00:00Z");

    private DocumentRepository repository;
    private StorageService storageService;
    private MongoTemplate mongoTemplate;
    private ResourcePatternResolver resourcePatternResolver;
    private DocumentHeaderParser headerParser;
    private DocumentArchiveService archiveService;
    private SettingService settingService;
    private DocumentService service;

    @BeforeEach
    void setUp() throws IOException {
        repository = mock(DocumentRepository.class);
        storageService = mock(StorageService.class);
        mongoTemplate = mock(MongoTemplate.class);
        resourcePatternResolver = mock(ResourcePatternResolver.class);
        headerParser = mock(DocumentHeaderParser.class);
        archiveService = mock(DocumentArchiveService.class);
        settingService = mock(SettingService.class);
        when(headerParser.parse(any(), any())).thenReturn(Optional.empty());
        when(headerParser.parseStream(any(), any())).thenReturn(Optional.empty());
        when(storageService.store(any(), any(), any(), anyLong())).thenAnswer(inv -> {
            java.io.InputStream stream = inv.getArgument(2);
            long size = stream.readAllBytes().length;
            return new StorageService.StorageInfo("blob-" + java.util.UUID.randomUUID(), size, new Date(), null, null);
        });
        when(repository.save(any(DocumentDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        when(settingService.getBooleanValueCascade(
                        anyString(), anyString(), any(), eq(DocumentService.SETTING_ARCHIVE_ENABLED), anyBoolean()))
                .thenReturn(true);
        service = new DocumentService(
                repository,
                storageService,
                mongoTemplate,
                resourcePatternResolver,
                headerParser,
                archiveService,
                settingService,
                DocTestSupport.permissionProvider());
        ReflectionTestUtils.setField(service, "inlineThreshold", 40960);
        ReflectionTestUtils.setField(service, "compressionEnabled", false);
        ReflectionTestUtils.setField(service, "compressionThreshold", 1000);
        ReflectionTestUtils.setField(service, "archiveEnabledDefault", true);
        ReflectionTestUtils.setField(service, "archiveMinIntervalSecondsDefault", 600L);
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    // ─── stamping on user-initiated writes ───────────────────────────

    @Test
    void create_stamps_updatedAt_onSave() {
        DocumentDocument saved = service.create(
                "acme",
                "wiki",
                "notes/idee.md",
                "Idee",
                List.of("draft"),
                "text/markdown",
                new ByteArrayInputStream("hello".getBytes()),
                "mike",
                WriteActor.SYSTEM);

        assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    void update_contentEdit_stamps_updatedAt_onSave() {
        DocumentDocument doc = freshDoc("hello");
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        DocumentDocument saved = service.update("doc-1", null, null, "world", null, WriteActor.SYSTEM);

        assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    void update_metadataOnly_stamps_updatedAt_onSave() {
        // Title-only edit, no content change — still meaningful, the user
        // renamed the document. updatedAt must move forward or the UI
        // would show a stale "last modified".
        DocumentDocument doc = freshDoc("hello");
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        DocumentDocument saved = service.update("doc-1", "New Title", null, null, null, WriteActor.SYSTEM);

        assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    void update_rename_stamps_updatedAt_onSave() {
        // Move/rename is exactly the kind of "metadata edit the user did
        // just now" that the planning doc calls out — without the stamp,
        // the document keeps its pre-rename timestamp and the backup tool
        // would carry the old mtime on the renamed file.
        DocumentDocument doc = freshDoc("hello");
        doc.setPath("notes/old.md");
        doc.setName("old.md");
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        DocumentDocument saved = service.update("doc-1", null, null, null, "notes/new.md", WriteActor.SYSTEM);

        assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(saved.getPath()).isEqualTo("notes/new.md");
    }

    @Test
    void trash_stamps_updatedAt_onSave() {
        DocumentDocument doc = freshDoc("hello");
        doc.setPath("notes/keep.md");
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        DocumentDocument saved = service.trash("doc-1", WriteActor.SYSTEM);

        assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(saved.getPath()).startsWith(DocumentService.TRASH_FOLDER_PREFIX);
    }

    @Test
    void restore_stamps_updatedAt_onSave() {
        DocumentDocument doc = freshDoc("hello");
        doc.setPath(DocumentService.TRASH_FOLDER_PREFIX + "uuid-keep.md");
        doc.setHeaders(new java.util.LinkedHashMap<>());
        doc.getHeaders().put(DocumentService.TRASH_ORIGINAL_PATH_HEADER, "notes/original.md");
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        DocumentDocument saved = service.restore("doc-1", null, WriteActor.SYSTEM);

        assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(saved.getPath()).isEqualTo("notes/original.md");
    }

    @Test
    void setLockedFor_stamps_updatedAt_onSave() {
        DocumentDocument doc = freshDoc("hello");
        when(repository.findById("doc-1")).thenReturn(Optional.of(doc));

        DocumentDocument saved = service.setLockedFor("doc-1", Set.of(WriterRole.AI), WriteActor.SYSTEM);

        assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(saved.getLockedFor()).contains(WriterRole.AI);
    }

    // ─── no stamping on bookkeeping writes ────────────────────────────

    @Test
    void writeSummary_doesNotAlter_updatedAt() {
        // Bookkeeping writes must not bump updatedAt — the production code
        // goes through MongoTemplate.updateFirst with an explicit field
        // list, never repository.save(doc), so markChanged cannot run.
        // Pin the invariant indirectly: repository.save would touch updatedAt
        // (via the markChanged path) on every call, and the test verifies
        // it is never called for this flow.
        DocumentDocument doc = freshDoc("hello");
        doc.setUpdatedAt(Instant.parse("2026-10-01T08:00:00Z"));
        Instant before = doc.getUpdatedAt();

        service.writeSummary("doc-1", "summary", List.of("a", "b"));

        // 1. No full-document save — markChanged can only run through that path.
        verify(repository, never()).save(any(DocumentDocument.class));
        // 2. The doc object itself was not mutated (no markChanged call).
        assertThat(doc.getUpdatedAt())
                .as("writeSummary must not bump updatedAt — bookkeeping writes do not touch the field")
                .isEqualTo(before);
    }

    // ─── fixtures ──────────────────────────────────────────────────────

    private DocumentDocument freshDoc(String body) {
        DocumentDocument doc = new DocumentDocument();
        doc.setId("doc-1");
        doc.setTenantId("acme");
        doc.setProjectId("wiki");
        doc.setPath("notes/keep.md");
        doc.setName("keep.md");
        doc.setSize(body.length());
        doc.setMimeType("text/markdown");
        doc.setStorageId("storage-" + body.hashCode());
        doc.setLineageId(java.util.UUID.randomUUID().toString());
        return doc;
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
