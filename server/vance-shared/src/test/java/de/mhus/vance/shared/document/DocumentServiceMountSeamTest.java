package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.mount.MountedStat;
import de.mhus.vance.shared.document.jaglan.JaglanAccessException;
import de.mhus.vance.shared.document.jaglan.JaglanPaths;
import de.mhus.vance.shared.document.jaglan.JaglanPort;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.storage.StorageService;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The seam where the {@code _ext/} namespace meets document lifecycle logic
 * that predates it.
 *
 * <p>A mounted document is a metadata shell: no {@code storageId}, and an id
 * <em>derived from its path</em> rather than minted by Mongo. Three
 * pre-existing paths did not know that — create, rename and versioning — and
 * each broke a different way. Pinned here because the failures are silent:
 * a duplicate-key that a folder listing books as a mount outage, a rename that
 * leaves a row nobody can resolve, an archive entry that restores as empty.
 *
 * <p>Spec: {@code specification/public/jaglan-system.md} §9.
 */
class DocumentServiceMountSeamTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String MOUNTED_PATH = "_ext/library/notes.md";

    private DocumentRepository repository;
    private JaglanPort port;
    private DocumentService service;

    private final WriteActor actor =
            WriteActor.user(SecurityContext.user("alice", TENANT, List.of()));

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        StorageService storageService = mock(StorageService.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        ResourcePatternResolver resourcePatternResolver = mock(ResourcePatternResolver.class);
        DocumentHeaderParser headerParser = mock(DocumentHeaderParser.class);
        DocumentArchiveService archiveService = mock(DocumentArchiveService.class);
        SettingService settingService = mock(SettingService.class);

        PermissionService permissionService = mock(PermissionService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PermissionService> psp = mock(ObjectProvider.class);
        when(psp.getObject()).thenReturn(permissionService);

        when(storageService.store(any(), any(), any())).thenAnswer(inv -> {
            java.io.InputStream stream = inv.getArgument(2);
            return new StorageService.StorageInfo(
                    "blob-1", stream.readAllBytes().length, new Date(), null, null);
        });
        when(headerParser.parse(any(), any())).thenReturn(Optional.empty());

        service = new DocumentService(
                repository, storageService, mongoTemplate,
                resourcePatternResolver, headerParser,
                archiveService, settingService, psp);
        ReflectionTestUtils.setField(service, "inlineThreshold", 40960);
        ReflectionTestUtils.setField(service, "compressionEnabled", false);
        ReflectionTestUtils.setField(service, "compressionThreshold", 1000);
        ReflectionTestUtils.setField(service, "archiveEnabledDefault", true);

        port = mock(JaglanPort.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JaglanPort> jp = mock(ObjectProvider.class);
        when(jp.getIfAvailable()).thenReturn(port);
        ReflectionTestUtils.setField(service, "jaglanPortProvider", jp);
    }

    // ─── create ─────────────────────────────────────────────────────────

    @Test
    void create_inAMount_usesTheDerivedIdRatherThanAGeneratedOne() {
        // A generated ObjectId produced a row with the right path and the wrong
        // _id. The next findByPath / folder listing then tried to upsert the
        // shell under the derived id, hit the unique (tenant, project, path)
        // index, and the DuplicateKeyException was booked as a mount outage —
        // the mount stayed broken and did not heal.
        when(repository.findByTenantIdAndProjectIdAndPath(TENANT, PROJECT, MOUNTED_PATH))
                .thenReturn(Optional.empty());
        when(port.write(any(), any(), any(), any(), any()))
                .thenReturn(new MountedStat("notes.md", false, 5, "text/markdown",
                        "etag-1", 1L, MountAccess.RW));
        when(repository.save(any(DocumentDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createText(TENANT, PROJECT, MOUNTED_PATH, null, null, "hello", "alice", actor);

        ArgumentCaptor<DocumentDocument> saved =
                ArgumentCaptor.forClass(DocumentDocument.class);
        org.mockito.Mockito.verify(repository).save(saved.capture());
        String expected = JaglanPaths.documentIdForPath(TENANT, PROJECT, MOUNTED_PATH);
        assertThat(saved.getValue().getId()).isEqualTo(expected);
        // Derived on both, matching the shell upsert: a purged-and-rewritten
        // row keeps its identity, and archives do not apply here anyway.
        assertThat(saved.getValue().getLineageId()).isEqualTo(expected);
        assertThat(saved.getValue().getStorageId()).isNull();
    }

    @Test
    void create_inAMount_whereTheSourceAlreadyHasTheFile_isRefused() {
        // The repository alone cannot answer "does this exist": a shell row
        // appears only once somebody browsed or stat'ed the entry, so a plain
        // index check let a create silently overwrite the source's file.
        DocumentDocument shell = mountedRow();
        when(repository.findByTenantIdAndProjectIdAndPath(TENANT, PROJECT, MOUNTED_PATH))
                .thenReturn(Optional.of(shell));

        assertThatThrownBy(() -> service.createText(
                TENANT, PROJECT, MOUNTED_PATH, null, null, "hello", "alice", actor))
                .isInstanceOf(DocumentService.DocumentAlreadyExistsException.class);
        org.mockito.Mockito.verify(port, org.mockito.Mockito.never())
                .write(any(), any(), any(), any(), any());
    }

    // ─── rename / move ──────────────────────────────────────────────────

    @Test
    void rename_withinAMount_isRefused() {
        // The id is derived from the path, so renaming the row alone leaves an
        // id that no longer matches the address — and nothing at the source is
        // touched, so the rename would be a lie either way.
        when(repository.findById("ext-1")).thenReturn(Optional.of(mountedRow()));

        assertThatThrownBy(() -> service.update(
                "ext-1", null, null, null, "_ext/library/renamed.md", actor))
                .isInstanceOf(JaglanAccessException.class)
                .hasMessageContaining("cannot be renamed");
    }

    @Test
    void moveOutOfAMount_isRefused() {
        // Worse than the rename: the row would become an ordinary document
        // without a storageId, so its content reads as empty with no error.
        when(repository.findById("ext-1")).thenReturn(Optional.of(mountedRow()));

        assertThatThrownBy(() -> service.update(
                "ext-1", null, null, null, "documents/notes.md", actor))
                .isInstanceOf(JaglanAccessException.class);
    }

    @Test
    void moveIntoAMount_isRefused() {
        DocumentDocument ordinary = DocumentDocument.builder()
                .tenantId(TENANT).projectId(PROJECT).path("documents/notes.md")
                .mimeType("text/markdown").lineageId("lin-1")
                .build();
        ordinary.setId("doc-1");
        when(repository.findById("doc-1")).thenReturn(Optional.of(ordinary));

        assertThatThrownBy(() -> service.update(
                "doc-1", null, null, null, MOUNTED_PATH, actor))
                .isInstanceOf(JaglanAccessException.class)
                .hasMessageContaining("cannot be moved into a mount");
    }

    // ─── versioning ─────────────────────────────────────────────────────

    @Test
    void archiveOnSave_isOffForMountedDocuments() {
        // lastArchivedAt is null and createdAt is set by the shell upsert, so
        // after the min-interval this returned true and the second save wrote
        // an archive row with storageId == null: a version the panel lists and
        // that restores as empty content.
        DocumentDocument mounted = mountedRow();
        mounted.setCreatedAt(java.time.Instant.now().minusSeconds(86400));

        assertThat(service.shouldArchiveOnSave(mounted)).isFalse();
    }

    @Test
    void createVersionNow_onAMountedDocument_isRefusedByName() {
        // The implicit path answers with a plain false; an explicit gesture has
        // to be refused by name — "what does not apply refuses rather than
        // quietly doing nothing" (§9).
        when(repository.findById("ext-1")).thenReturn(Optional.of(mountedRow()));

        assertThatThrownBy(() -> service.createVersionNow("ext-1", actor))
                .isInstanceOf(JaglanAccessException.class)
                .hasMessageContaining("not versioned");
    }

    @Test
    void countArchives_onAMountedDocument_isZeroWithoutAskingTheArchive() {
        // Answered rather than refused: this feeds a badge on a listing, and an
        // exception in a decoration would break the listing over an absent
        // feature.
        assertThat(service.countArchives(mountedRow())).isZero();
    }

    private DocumentDocument mountedRow() {
        DocumentDocument doc = DocumentDocument.builder()
                .tenantId(TENANT).projectId(PROJECT).path(MOUNTED_PATH)
                .name("notes.md").mimeType("text/markdown")
                .lineageId(JaglanPaths.documentIdForPath(TENANT, PROJECT, MOUNTED_PATH))
                .build();
        doc.setId("ext-1");
        return doc;
    }
}
