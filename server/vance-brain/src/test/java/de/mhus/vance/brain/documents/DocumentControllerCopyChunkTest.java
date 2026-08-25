package de.mhus.vance.brain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.documents.DocumentCopyChunkRequest;
import de.mhus.vance.api.documents.DocumentCopyChunkResponse;
import de.mhus.vance.api.documents.WriterRole;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for the chunked-copy endpoint ({@link DocumentController#copyChunk}).
 *
 * <p>The copy endpoint mirrors the move endpoint's chunk loop but creates a new
 * document in the target project instead of updating the source's path. These
 * tests verify the core behaviours: single-id copy, folder scan, cross-project
 * copy, permission denial, collision skipping and the {@code overwrite} flag
 * that turns a collision into an in-place replace — all with mocked
 * {@link DocumentService} and {@link RequestAuthority} so no Spring context is
 * needed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentControllerCopyChunkTest {

    @Mock private DocumentService documentService;
    @Mock private RequestAuthority authority;
    @Mock private HttpServletRequest httpRequest;

    private DocumentController controller;

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj-a";
    private static final String TARGET_PROJECT = "proj-b";
    private static final String USERNAME = "alice";

    @BeforeEach
    void setUp() {
        controller = new DocumentController(documentService, authority);
        // Simulate an authenticated request — writerIdentity() and actor()
        // both call authority.contextOf(request).
        when(httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME))
                .thenReturn(USERNAME);
        when(authority.contextOf(httpRequest))
                .thenReturn(SecurityContext.user(USERNAME, TENANT, List.of()));
    }

    private DocumentDocument doc(String id, String path) {
        return DocumentDocument.builder()
                .id(id)
                .tenantId(TENANT)
                .projectId(PROJECT)
                .path(path)
                .mimeType("text/markdown")
                .title("Test Doc")
                .tags(List.of("tag1"))
                .autoSummary(false)
                .build();
    }

    // ── Single-id copy (same project) ──────────────────────────────

    @Test
    void copyChunk_singleId_sameProject_copiesToTargetFolder() {
        DocumentDocument source = doc("d1", "notes/ch1.md");
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        when(documentService.readContent(source)).thenReturn("# Hello");
        when(authority.check(eq(httpRequest), any(Resource.Document.class), eq(Action.READ)))
                .thenReturn(true);
        when(authority.check(eq(httpRequest), any(Resource.Document.class), eq(Action.CREATE)))
                .thenReturn(true);
        when(documentService.create(
                        eq(TENANT), eq(PROJECT), eq("archive/ch1.md"),
                        any(), any(), any(), any(InputStream.class), any(),
                        any(), any(), any()))
                .thenReturn(source);

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .ids(List.of("d1"))
                .targetFolder("archive")
                .build();

        DocumentCopyChunkResponse res = controller.copyChunk(
                TENANT, PROJECT, req, null, httpRequest);

        assertThat(res.getCopied()).isEqualTo(1);
        assertThat(res.getSkipped()).isZero();
        assertThat(res.isDone()).isTrue();
        assertThat(res.getCursor()).isNull();
        verify(documentService).create(
                eq(TENANT), eq(PROJECT), eq("archive/ch1.md"),
                eq("Test Doc"), eq(List.of("tag1")), eq("text/markdown"),
                any(InputStream.class), eq(USERNAME),
                eq(Boolean.FALSE), eq(null), any());
    }

    // ── Cross-project copy ────────────────────────────────────────

    @Test
    void copyChunk_singleId_crossProject_copiesToTargetProject() {
        DocumentDocument source = doc("d1", "notes/ch1.md");
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        when(documentService.readContent(source)).thenReturn("# Hello");
        when(authority.check(eq(httpRequest), any(Resource.Document.class), any(Action.class)))
                .thenReturn(true);
        when(documentService.create(
                        eq(TENANT), eq(TARGET_PROJECT), anyString(),
                        any(), any(), any(), any(InputStream.class), any(),
                        any(), any(), any()))
                .thenReturn(source);

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .ids(List.of("d1"))
                .targetProjectId(TARGET_PROJECT)
                .targetFolder("")
                .build();

        DocumentCopyChunkResponse res = controller.copyChunk(
                TENANT, PROJECT, req, null, httpRequest);

        assertThat(res.getCopied()).isEqualTo(1);
        // Verify create was called with the target project, not the source.
        verify(documentService).create(
                eq(TENANT), eq(TARGET_PROJECT), eq("ch1.md"),
                any(), any(), any(), any(InputStream.class), any(),
                any(), any(), any());
    }

    // ── Permission denial: no READ on source ──────────────────────

    @Test
    void copyChunk_noReadPermission_skipsDocument() {
        DocumentDocument source = doc("d1", "notes/ch1.md");
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        // READ denied, CREATE allowed — but READ is checked first.
        when(authority.check(eq(httpRequest), any(Resource.Document.class), eq(Action.READ)))
                .thenReturn(false);
        when(authority.check(eq(httpRequest), any(Resource.Document.class), eq(Action.CREATE)))
                .thenReturn(true);

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .ids(List.of("d1"))
                .targetFolder("archive")
                .build();

        DocumentCopyChunkResponse res = controller.copyChunk(
                TENANT, PROJECT, req, null, httpRequest);

        assertThat(res.getCopied()).isZero();
        assertThat(res.getSkipped()).isEqualTo(1);
        verify(documentService, never()).create(
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(InputStream.class), any(),
                any(), any(), any());
    }

    // ── Permission denial: no CREATE on destination ────────────────

    @Test
    void copyChunk_noCreatePermission_skipsDocument() {
        DocumentDocument source = doc("d1", "notes/ch1.md");
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        when(authority.check(eq(httpRequest), any(Resource.Document.class), eq(Action.READ)))
                .thenReturn(true);
        when(authority.check(eq(httpRequest), any(Resource.Document.class), eq(Action.CREATE)))
                .thenReturn(false);

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .ids(List.of("d1"))
                .targetFolder("archive")
                .build();

        DocumentCopyChunkResponse res = controller.copyChunk(
                TENANT, PROJECT, req, null, httpRequest);

        assertThat(res.getCopied()).isZero();
        assertThat(res.getSkipped()).isEqualTo(1);
        verify(documentService, never()).create(
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(InputStream.class), any(),
                any(), any(), any());
    }

    // ── Collision: target path already exists ─────────────────────

    @Test
    void copyChunk_collisionInTarget_skipsDocument() {
        DocumentDocument source = doc("d1", "notes/ch1.md");
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        when(documentService.readContent(source)).thenReturn("# Hello");
        when(authority.check(eq(httpRequest), any(Resource.Document.class), any(Action.class)))
                .thenReturn(true);
        when(documentService.create(
                        anyString(), anyString(), anyString(),
                        any(), any(), any(), any(InputStream.class), any(),
                        any(), any(), any()))
                .thenThrow(new DocumentService.DocumentAlreadyExistsException("exists"));

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .ids(List.of("d1"))
                .targetFolder("archive")
                .build();

        DocumentCopyChunkResponse res = controller.copyChunk(
                TENANT, PROJECT, req, null, httpRequest);

        assertThat(res.getCopied()).isZero();
        assertThat(res.getSkipped()).isEqualTo(1);
    }

    // ── Overwrite: collision replaces the target ──────────────────

    @Test
    void copyChunk_overwrite_existingTarget_replacesInsteadOfSkipping() {
        DocumentDocument source = doc("d1", "notes/ch1.md");
        DocumentDocument target = doc("d2", "archive/ch1.md");
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        when(documentService.findByPath(TENANT, PROJECT, "archive/ch1.md"))
                .thenReturn(Optional.of(target));
        when(documentService.readContent(source)).thenReturn("# Hello");
        when(authority.check(eq(httpRequest), any(Resource.Document.class), any(Action.class)))
                .thenReturn(true);
        when(documentService.replaceContent(
                        anyString(), any(InputStream.class), any(), any(), any()))
                .thenReturn(target);

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .ids(List.of("d1"))
                .targetFolder("archive")
                .overwrite(true)
                .build();

        DocumentCopyChunkResponse res = controller.copyChunk(
                TENANT, PROJECT, req, null, httpRequest);

        assertThat(res.getOverwritten()).isEqualTo(1);
        assertThat(res.getCopied()).isZero();
        assertThat(res.getSkipped()).isZero();
        verify(documentService).replaceContent(
                eq("d2"), any(InputStream.class), eq("text/markdown"), any(), any());
        verify(documentService, never()).create(
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(InputStream.class), any(),
                any(), any(), any());
    }

    @Test
    void copyChunk_overwrite_noWritePermissionOnTarget_skipsDocument() {
        DocumentDocument source = doc("d1", "notes/ch1.md");
        DocumentDocument target = doc("d2", "archive/ch1.md");
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        when(documentService.findByPath(TENANT, PROJECT, "archive/ch1.md"))
                .thenReturn(Optional.of(target));
        when(authority.check(eq(httpRequest), any(Resource.Document.class), eq(Action.READ)))
                .thenReturn(true);
        // CREATE would be granted — overwriting must not fall back to it.
        when(authority.check(eq(httpRequest), any(Resource.Document.class), eq(Action.CREATE)))
                .thenReturn(true);
        when(authority.check(eq(httpRequest), any(Resource.Document.class), eq(Action.WRITE)))
                .thenReturn(false);

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .ids(List.of("d1"))
                .targetFolder("archive")
                .overwrite(true)
                .build();

        DocumentCopyChunkResponse res = controller.copyChunk(
                TENANT, PROJECT, req, null, httpRequest);

        assertThat(res.getSkipped()).isEqualTo(1);
        assertThat(res.getOverwritten()).isZero();
        verify(documentService, never()).replaceContent(
                anyString(), any(InputStream.class), any(), any(), any());
    }

    @Test
    void copyChunk_overwrite_lockedTarget_skipsWithoutFailingTheChunk() {
        DocumentDocument source = doc("d1", "notes/ch1.md");
        DocumentDocument target = doc("d2", "archive/ch1.md");
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        when(documentService.findByPath(TENANT, PROJECT, "archive/ch1.md"))
                .thenReturn(Optional.of(target));
        when(documentService.readContent(source)).thenReturn("# Hello");
        when(authority.check(eq(httpRequest), any(Resource.Document.class), any(Action.class)))
                .thenReturn(true);
        when(documentService.replaceContent(
                        anyString(), any(InputStream.class), any(), any(), any()))
                .thenThrow(new DocumentService.DocumentLockedException(
                        WriterRole.USER, java.util.Set.of(WriterRole.USER)));

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .ids(List.of("d1"))
                .targetFolder("archive")
                .overwrite(true)
                .build();

        DocumentCopyChunkResponse res = controller.copyChunk(
                TENANT, PROJECT, req, null, httpRequest);

        assertThat(res.getSkipped()).isEqualTo(1);
        assertThat(res.getOverwritten()).isZero();
        assertThat(res.isDone()).isTrue();
    }

    @Test
    void copyChunk_overwrite_documentOntoItself_skipsDocument() {
        // Target folder == the document's own folder: the collision found at
        // the destination IS the source.
        DocumentDocument source = doc("d1", "notes/ch1.md");
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        when(documentService.findByPath(TENANT, PROJECT, "notes/ch1.md"))
                .thenReturn(Optional.of(source));
        when(authority.check(eq(httpRequest), any(Resource.Document.class), any(Action.class)))
                .thenReturn(true);

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .ids(List.of("d1"))
                .targetFolder("notes")
                .overwrite(true)
                .build();

        DocumentCopyChunkResponse res = controller.copyChunk(
                TENANT, PROJECT, req, null, httpRequest);

        assertThat(res.getSkipped()).isEqualTo(1);
        assertThat(res.getOverwritten()).isZero();
        verify(documentService, never()).replaceContent(
                anyString(), any(InputStream.class), any(), any(), any());
    }

    @Test
    void copyChunk_overwrite_freeTargetPath_stillCreates() {
        DocumentDocument source = doc("d1", "notes/ch1.md");
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        when(documentService.findByPath(TENANT, PROJECT, "archive/ch1.md"))
                .thenReturn(Optional.empty());
        when(documentService.readContent(source)).thenReturn("# Hello");
        when(authority.check(eq(httpRequest), any(Resource.Document.class), any(Action.class)))
                .thenReturn(true);
        when(documentService.create(
                        anyString(), anyString(), anyString(),
                        any(), any(), any(), any(InputStream.class), any(),
                        any(), any(), any()))
                .thenReturn(source);

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .ids(List.of("d1"))
                .targetFolder("archive")
                .overwrite(true)
                .build();

        DocumentCopyChunkResponse res = controller.copyChunk(
                TENANT, PROJECT, req, null, httpRequest);

        assertThat(res.getCopied()).isEqualTo(1);
        assertThat(res.getOverwritten()).isZero();
    }

    @Test
    void copyChunk_withoutOverwrite_neverLooksUpTheTarget() {
        DocumentDocument source = doc("d1", "notes/ch1.md");
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        when(documentService.readContent(source)).thenReturn("# Hello");
        when(authority.check(eq(httpRequest), any(Resource.Document.class), any(Action.class)))
                .thenReturn(true);
        when(documentService.create(
                        anyString(), anyString(), anyString(),
                        any(), any(), any(), any(InputStream.class), any(),
                        any(), any(), any()))
                .thenReturn(source);

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .ids(List.of("d1"))
                .targetFolder("archive")
                .build();

        controller.copyChunk(TENANT, PROJECT, req, null, httpRequest);

        // The collision stays the create funnel's business — no extra read.
        verify(documentService, never()).findByPath(anyString(), anyString(), anyString());
    }

    // ── Folder scan with cursor paging ────────────────────────────

    @Test
    void copyChunk_folderScan_pagesViaCursor() {
        DocumentDocument d1 = doc("d1", "docs/a.md");
        DocumentDocument d2 = doc("d2", "docs/b.md");
        when(documentService.listUnderFoldersAfter(
                        eq(TENANT), eq(PROJECT), eq(List.of("docs/")), eq(null), eq(25)))
                .thenReturn(List.of(d1, d2));
        when(documentService.readContent(any(DocumentDocument.class))).thenReturn("content");
        when(authority.check(eq(httpRequest), any(Resource.Document.class), any(Action.class)))
                .thenReturn(true);
        when(documentService.create(
                        anyString(), anyString(), anyString(),
                        any(), any(), any(), any(InputStream.class), any(),
                        any(), any(), any()))
                .thenReturn(d1);

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .folders(List.of("docs/"))
                .targetFolder("backup")
                .build();

        DocumentCopyChunkResponse res = controller.copyChunk(
                TENANT, PROJECT, req, null, httpRequest);

        // batch size < limit → done
        assertThat(res.getCopied()).isEqualTo(2);
        assertThat(res.isDone()).isTrue();
        assertThat(res.getCursor()).isNull();
    }

    @Test
    void copyChunk_folderScan_notDoneWhenBatchEqualsLimit() {
        // Simulate a full batch — the server should return done=false with a cursor.
        DocumentDocument d1 = doc("d1", "docs/a.md");
        when(documentService.listUnderFoldersAfter(
                        eq(TENANT), eq(PROJECT), eq(List.of("docs/")), eq(null), eq(2)))
                .thenReturn(List.of(d1, d1)); // same doc twice is fine for the mock
        when(documentService.readContent(any(DocumentDocument.class))).thenReturn("content");
        when(authority.check(eq(httpRequest), any(Resource.Document.class), any(Action.class)))
                .thenReturn(true);
        when(documentService.create(
                        anyString(), anyString(), anyString(),
                        any(), any(), any(), any(InputStream.class), any(),
                        any(), any(), any()))
                .thenReturn(d1);

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .folders(List.of("docs/"))
                .targetFolder("backup")
                .limit(2)
                .build();

        DocumentCopyChunkResponse res = controller.copyChunk(
                TENANT, PROJECT, req, null, httpRequest);

        assertThat(res.getCopied()).isEqualTo(2);
        assertThat(res.isDone()).isFalse();
        assertThat(res.getCursor()).isEqualTo("docs/a.md");
    }

    // ── Empty selection ───────────────────────────────────────────

    @Test
    void copyChunk_noIdsNoFolders_returnsEmptyDone() {
        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .targetFolder("archive")
                .build();

        DocumentCopyChunkResponse res = controller.copyChunk(
                TENANT, PROJECT, req, null, httpRequest);

        assertThat(res.getCopied()).isZero();
        assertThat(res.getSkipped()).isZero();
        assertThat(res.isDone()).isTrue();
        verify(documentService, never()).create(
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(InputStream.class), any(),
                any(), any(), any());
    }

    // ── Id inside a selected folder is skipped in the ids phase ───

    @Test
    void copyChunk_idInsideSelectedFolder_skippedInIdsPhase() {
        DocumentDocument source = doc("d1", "docs/ch1.md");
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        when(documentService.listUnderFoldersAfter(
                        eq(TENANT), eq(PROJECT), eq(List.of("docs/")), eq(null), eq(25)))
                .thenReturn(List.of(source));
        when(documentService.readContent(source)).thenReturn("content");
        when(authority.check(eq(httpRequest), any(Resource.Document.class), any(Action.class)))
                .thenReturn(true);
        when(documentService.create(
                        anyString(), anyString(), anyString(),
                        any(), any(), any(), any(InputStream.class), any(),
                        any(), any(), any()))
                .thenReturn(source);

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .ids(List.of("d1"))
                .folders(List.of("docs/"))
                .targetFolder("backup")
                .build();

        controller.copyChunk(TENANT, PROJECT, req, null, httpRequest);

        // The id was inside the folder, so it should NOT be processed in the
        // ids phase (only in the folder scan). Verify create was called once
        // (from the folder scan), not twice.
        verify(documentService, org.mockito.Mockito.times(1)).create(
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(InputStream.class), any(),
                any(), any(), any());
    }

    // ── Folder structure preservation ────────────────────────────

    @Test
    void copyChunk_folderStructurePreserved() {
        DocumentDocument source = doc("d1", "docs/sub/deep.md");
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        when(documentService.readContent(source)).thenReturn("# Deep");
        when(authority.check(eq(httpRequest), any(Resource.Document.class), any(Action.class)))
                .thenReturn(true);

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .ids(List.of("d1"))
                .folders(List.of("docs/"))
                .targetFolder("backup")
                .build();

        controller.copyChunk(TENANT, PROJECT, req, null, httpRequest);

        // moveNewPath preserves the folder structure under the target:
        // docs/sub/deep.md → backup/docs/sub/deep.md
        // But since the id IS inside the selected folder, it's skipped in
        // the ids phase and processed in the folder scan. The folder scan
        // uses listUnderFoldersAfter which returns the doc.
        when(documentService.listUnderFoldersAfter(
                eq(TENANT), eq(PROJECT), eq(List.of("docs/")), eq(null), eq(25)))
                .thenReturn(List.of(source));

        controller.copyChunk(TENANT, PROJECT, req, null, httpRequest);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentService, org.mockito.Mockito.atLeastOnce()).create(
                eq(TENANT), eq(PROJECT), pathCaptor.capture(),
                any(), any(), any(), any(InputStream.class), any(),
                any(), any(), any());
        assertThat(pathCaptor.getValue()).isEqualTo("backup/docs/sub/deep.md");
    }

    // ── Default target project = source project ───────────────────

    @Test
    void copyChunk_blankTargetProject_defaultsToSourceProject() {
        DocumentDocument source = doc("d1", "file.md");
        when(documentService.findById("d1")).thenReturn(Optional.of(source));
        when(documentService.readContent(source)).thenReturn("content");
        when(authority.check(eq(httpRequest), any(Resource.Document.class), any(Action.class)))
                .thenReturn(true);
        when(documentService.create(
                        eq(TENANT), eq(PROJECT), anyString(),
                        any(), any(), any(), any(InputStream.class), any(),
                        any(), any(), any()))
                .thenReturn(source);

        DocumentCopyChunkRequest req = DocumentCopyChunkRequest.builder()
                .ids(List.of("d1"))
                .targetProjectId("")   // blank → source project
                .targetFolder("copy")
                .build();

        DocumentCopyChunkResponse res = controller.copyChunk(
                TENANT, PROJECT, req, null, httpRequest);

        assertThat(res.getCopied()).isEqualTo(1);
        verify(documentService).create(
                eq(TENANT), eq(PROJECT), eq("copy/file.md"),
                any(), any(), any(), any(InputStream.class), any(),
                any(), any(), any());
    }
}
