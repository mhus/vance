package de.mhus.vance.addon.brain.issues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The number-reservation path (app-issues.md §12.4) is the module's most
 * delicate logic: {@code number = max(nextNumber, maxExisting+1) + attempt}
 * with a unique-index-clash retry. A regression in the floor/attempt math
 * would silently reuse or skip issue numbers, so pin it here.
 */
class IssuesServiceTest {

    private final DocumentService documentService = mock(DocumentService.class);
    private final IssuesFolderReader folderReader = mock(IssuesFolderReader.class);
    private final SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
    private IssuesService service;

    private DocumentDocument manifest;

    @BeforeEach
    void setUp() {
        service = new IssuesService(documentService, folderReader, contextFactory);
        manifest = DocumentDocument.builder()
                .id("mid").tenantId("acme").projectId("proj")
                .path("backend/_app.yaml").title("Backend").build();
    }

    private void stubScan(int nextNumber, int maxExisting) {
        IssuesConfig config = new IssuesConfig(
                "Backend", null, "items", "archive", nextNumber, List.of());
        when(folderReader.scan("acme", "proj", "backend"))
                .thenReturn(new IssuesFolderReader.Scan("backend", manifest, config, List.of()));
        when(folderReader.maxNumber(eq("acme"), eq("proj"), eq("backend"), any()))
                .thenReturn(maxExisting);
    }

    private DocumentDocument stored(String path) {
        return DocumentDocument.builder()
                .id("d").tenantId("acme").projectId("proj").path(path).build();
    }

    private String createdPath() {
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(documentService).create(anyString(), anyString(), path.capture(),
                any(), any(), any(), any(InputStream.class), any(), any());
        return path.getValue();
    }

    @Test
    void createIssue_usesNextNumber_whenAboveExistingFloor() throws Exception {
        stubScan(/*nextNumber*/ 5, /*maxExisting*/ 2);
        when(documentService.create(anyString(), anyString(), anyString(),
                any(), any(), any(), any(InputStream.class), any(), any()))
                .thenReturn(stored("backend/items/5-x.md"));

        service.createIssue("acme", "proj", "backend", "X", null, null, null, null, "u");

        assertThat(createdPath()).isEqualTo("backend/items/5-x.md");
    }

    @Test
    void createIssue_floorsToMaxExistingPlusOne_whenCounterStale() throws Exception {
        stubScan(/*nextNumber*/ 1, /*maxExisting*/ 9); // counter stale → floor 10 wins
        when(documentService.create(anyString(), anyString(), anyString(),
                any(), any(), any(), any(InputStream.class), any(), any()))
                .thenReturn(stored("backend/items/10-x.md"));

        service.createIssue("acme", "proj", "backend", "X", null, null, null, null, "u");

        assertThat(createdPath()).contains("/items/10-");
    }

    @Test
    void createIssue_retriesNextNumber_onUniqueIndexClash() throws Exception {
        stubScan(/*nextNumber*/ 1, /*maxExisting*/ 0); // number 1, then 2 on retry
        when(documentService.create(anyString(), anyString(), anyString(),
                any(), any(), any(), any(InputStream.class), any(), any()))
                .thenThrow(new DocumentService.DocumentAlreadyExistsException("taken"))
                .thenReturn(stored("backend/items/2-x.md"));

        service.createIssue("acme", "proj", "backend", "X", null, null, null, null, "u");

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(documentService, org.mockito.Mockito.times(2)).create(anyString(), anyString(),
                path.capture(), any(), any(), any(), any(InputStream.class), any(), any());
        assertThat(path.getAllValues().get(0)).contains("/items/1-");
        assertThat(path.getAllValues().get(1)).contains("/items/2-");
    }

    @Test
    void createIssue_throws_afterExhaustingNumberAttempts() {
        stubScan(1, 0);
        when(documentService.create(anyString(), anyString(), anyString(),
                any(), any(), any(), any(InputStream.class), any(), any()))
                .thenThrow(new DocumentService.DocumentAlreadyExistsException("taken"));

        assertThatThrownBy(() ->
                service.createIssue("acme", "proj", "backend", "X", null, null, null, null, "u"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("reserve a free issue number");
    }

    @Test
    void createIssue_blankTitle_rejected() {
        assertThatThrownBy(() ->
                service.createIssue("acme", "proj", "backend", "  ", null, null, null, null, "u"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("title is required");
    }
}
