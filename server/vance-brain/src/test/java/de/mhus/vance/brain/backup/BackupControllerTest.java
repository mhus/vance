package de.mhus.vance.brain.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.backup.BackupEntriesRequest;
import de.mhus.vance.api.backup.BackupEntriesResponse;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.integration.IntegrationTokenService;
import de.mhus.vance.shared.project.ProjectService;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The contract the {@code vance-liver} client relies on: paging follows the
 * scanned rows, the deny list is exactly what the client sent, and an
 * unchanged document answers {@code 304} without reading its content.
 */
class BackupControllerTest {

    private DocumentService documentService;
    private BackupController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        BackupAccessService access = mock(BackupAccessService.class);
        when(access.load("acme")).thenReturn(BackupAccessConfig.DEFAULT_ALLOWED);
        controller = new BackupController(
                documentService,
                mock(ProjectService.class),
                mock(IntegrationTokenService.class),
                access,
                mock(RequestAuthority.class),
                mock(SecurityContextFactory.class));
        request = new MockHttpServletRequest();
        request.setAttribute(AccessFilterBase.ATTR_USERNAME, "mike");
    }

    @Test
    void listEntries_cursorFollowsScannedRows() {
        // The service filtered one row away in the JVM: one entry back, but
        // the scan was full — the client must be told to keep paging.
        when(documentService.listBackupPage(eq("acme"), eq("wiki"), any(), any(), anyInt()))
                .thenReturn(new DocumentService.BackupPage(List.of(doc("a.md", "s1")), "last-scanned-id"));

        BackupEntriesResponse resp = controller.listEntries("acme", "wiki", new BackupEntriesRequest(), request);

        assertThat(resp.getEntries()).hasSize(1);
        assertThat(resp.getNextCursor()).isEqualTo("last-scanned-id");
    }

    @Test
    void listEntries_passesClientDenyUnchanged() {
        when(documentService.listBackupPage(eq("acme"), eq("wiki"), any(), any(), anyInt()))
                .thenReturn(new DocumentService.BackupPage(List.of(), null));
        BackupEntriesRequest req = new BackupEntriesRequest();
        req.setDeny(List.of("secrets/**"));
        req.setProjectDeny(List.of("drafts/**"));

        controller.listEntries("acme", "wiki", req, request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deny = ArgumentCaptor.forClass(List.class);
        verify(documentService).listBackupPage(eq("acme"), eq("wiki"), deny.capture(), any(), anyInt());
        // No server-side "_vance/**": the default is the client's to remove.
        assertThat(deny.getValue()).containsExactly("secrets/**", "drafts/**");
    }

    @Test
    void fetchContent_matchingEtag_isNotModifiedWithoutReadingContent() {
        DocumentDocument doc = doc("a.md", "storage-1");
        when(documentService.findById("doc-a.md")).thenReturn(Optional.of(doc));

        ResponseEntity<InputStreamResource> resp =
                controller.fetchContent("acme", "doc-a.md", "\"storage-1\"", request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        verify(documentService, never()).loadContent(any());
    }

    @Test
    void fetchContent_otherEtag_streamsContent() {
        DocumentDocument doc = doc("a.md", "storage-2");
        when(documentService.findById("doc-a.md")).thenReturn(Optional.of(doc));
        when(documentService.loadContent(doc)).thenReturn(InputStream.nullInputStream());

        ResponseEntity<InputStreamResource> resp =
                controller.fetchContent("acme", "doc-a.md", "\"storage-1\"", request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getETag()).isEqualTo("\"storage-2\"");
    }

    private static DocumentDocument doc(String path, String storageId) {
        DocumentDocument doc = new DocumentDocument();
        doc.setId("doc-" + path);
        doc.setTenantId("acme");
        doc.setProjectId("wiki");
        doc.setPath(path);
        doc.setName(path);
        doc.setStorageId(storageId);
        doc.setMimeType("text/markdown");
        return doc;
    }
}
