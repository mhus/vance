package de.mhus.vance.brain.tools.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.DocumentStatus;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Existing-doc handling for {@link DocImportUrlTool}. The HTTP path is not
 * exercised here — Vance's deadlock-class bug was that an LLM that called
 * {@code doc_import_url} a second time on the same URL hit a hard error
 * and went into a recovery loop. These tests pin down the idempotent
 * behaviour: 'reuse' (default) returns the existing doc without fetching,
 * 'error' is the explicit strict path, and unknown values are rejected
 * before any network call.
 */
class DocImportUrlToolTest {

    private static final ToolInvocationContext CTX = new ToolInvocationContext(
            "mhus", "vibecoding", "sess_abc", "proc_xyz", "j3sus");
    private static final String URL = "https://example.com/article";
    private static final String AUTO_PATH = "documents/article";

    private EddieContext eddieContext;
    private DocumentService documentService;
    private DocImportUrlTool tool;

    @BeforeEach
    void setUp() {
        eddieContext = mock(EddieContext.class);
        documentService = mock(DocumentService.class);
        tool = new DocImportUrlTool(eddieContext, documentService);

        ProjectDocument project = ProjectDocument.builder()
                .tenantId("mhus").name("vibecoding").build();
        when(eddieContext.resolveProject(any(), eq(CTX), eq(false))).thenReturn(project);
    }

    @Test
    void existingDoc_reuseDefault_returnsExistingWithoutFetch() {
        DocumentDocument existing = existingDoc();
        when(documentService.findByPath("mhus", "vibecoding", AUTO_PATH))
                .thenReturn(Optional.of(existing));

        Map<String, Object> out = tool.invoke(Map.of("url", URL), CTX);

        assertThat(out)
                .containsEntry("id", "doc-1")
                .containsEntry("path", AUTO_PATH)
                .containsEntry("reused", true)
                .containsEntry("sourceUrl", URL)
                .doesNotContainKey("httpStatus");
        verify(documentService, never()).create(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(documentService, never()).replaceContent(
                any(), any(), any(String.class));
    }

    @Test
    void existingDoc_ifExistsError_throwsWithHelpfulMessage() {
        when(documentService.findByPath("mhus", "vibecoding", AUTO_PATH))
                .thenReturn(Optional.of(existingDoc()));

        assertThatThrownBy(() -> tool.invoke(
                Map.of("url", URL, "ifExists", "error"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("already exists")
                .hasMessageContaining("ifExists='reuse'")
                .hasMessageContaining("ifExists='update'");
    }

    @Test
    void invalidIfExists_rejectedBeforeFetch() {
        // No findByPath stub — we want to confirm the rejection happens
        // before the existence check too, but mock returns empty by default
        // either way; the assertion is that the call never reaches create().
        assertThatThrownBy(() -> tool.invoke(
                Map.of("url", URL, "ifExists", "overwrite"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("ifExists")
                .hasMessageContaining("overwrite");
        verify(documentService, never()).create(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void existingDoc_ifExistsReuseCaseInsensitive() {
        when(documentService.findByPath("mhus", "vibecoding", AUTO_PATH))
                .thenReturn(Optional.of(existingDoc()));

        Map<String, Object> out = tool.invoke(
                Map.of("url", URL, "ifExists", "REUSE"), CTX);

        assertThat(out).containsEntry("reused", true);
    }

    private static DocumentDocument existingDoc() {
        return DocumentDocument.builder()
                .id("doc-1")
                .tenantId("mhus")
                .projectId("vibecoding")
                .path(AUTO_PATH)
                .name("article")
                .title("Imported article")
                .mimeType("text/html")
                .size(1234L)
                .tags(new java.util.ArrayList<>(List.of("imported")))
                .status(DocumentStatus.ACTIVE)
                .build();
    }
}
