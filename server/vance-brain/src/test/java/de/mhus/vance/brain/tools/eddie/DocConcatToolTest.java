package de.mhus.vance.brain.tools.eddie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link DocConcatTool}. Mocks the DocumentService /
 * EddieContext dependencies and verifies the tool's contract: read N
 * sources in declared order, glue with the configured separator,
 * apply optional header/footer, write target via createText.
 */
class DocConcatToolTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "story-project";
    private static final String USER = "u-1";

    private DocumentService documentService;
    private EddieContext eddieContext;
    private DocConcatTool tool;
    private ToolInvocationContext ctx;
    private ProjectDocument project;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        eddieContext = mock(EddieContext.class);
        tool = new DocConcatTool(eddieContext, documentService);
        ctx = new ToolInvocationContext(TENANT, PROJECT, null, null, USER);
        project = mock(ProjectDocument.class);
        when(project.getName()).thenReturn(PROJECT);
        when(eddieContext.resolveProject(any(), any(), eq(false))).thenReturn(project);
    }

    @Test
    void concatenatesSourcesInOrderWithDefaultSeparator() {
        stubInline("essay/chapters/01-anfang.md", "Marvin verlor seine Schrauben.");
        stubInline("essay/chapters/02-mitte.md", "Sie waren weg, restlos.");
        stubInline("essay/chapters/03-ende.md", "Niemand hatte sie gesehen.");
        DocumentDocument created = stubCreate(
                "essay/final-essay.md",
                "Marvin verlor seine Schrauben.\n\nSie waren weg, restlos.\n\nNiemand hatte sie gesehen.");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sources", List.of(
                "essay/chapters/01-anfang.md",
                "essay/chapters/02-mitte.md",
                "essay/chapters/03-ende.md"));
        params.put("target", "essay/final-essay.md");

        Map<String, Object> out = tool.invoke(params, ctx);

        assertThat(out)
                .containsEntry("path", "essay/final-essay.md")
                .containsEntry("id", created.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) out.get("sources");
        assertThat(sources).hasSize(3);
        assertThat(sources.get(0)).containsEntry("path", "essay/chapters/01-anfang.md");
    }

    @Test
    void appliesCustomSeparatorHeaderAndFooter() {
        stubInline("a.md", "Aaa");
        stubInline("b.md", "Bbb");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sources", List.of("a.md", "b.md"));
        params.put("target", "out.md");
        params.put("separator", "\n---\n");
        params.put("header", "[HEADER]");
        params.put("footer", "[FOOTER]");
        params.put("title", "Combined");

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        DocumentDocument created = mock(DocumentDocument.class);
        when(created.getId()).thenReturn("merged-id");
        when(created.getProjectId()).thenReturn(PROJECT);
        when(created.getPath()).thenReturn("out.md");
        when(created.getSize()).thenReturn(20L);
        when(documentService.createText(
                eq(TENANT), eq(PROJECT), eq("out.md"),
                eq("Combined"), any(),
                contentCaptor.capture(), eq(USER)))
                .thenReturn(created);

        tool.invoke(params, ctx);

        assertThat(contentCaptor.getValue())
                .isEqualTo("[HEADER]\n---\nAaa\n---\nBbb\n---\n[FOOTER]");
    }

    @Test
    void emptySourcesIsRejected() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sources", List.of());
        params.put("target", "out.md");

        assertThatThrownBy(() -> tool.invoke(params, ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("non-empty list");
    }

    @Test
    void missingTargetIsRejected() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sources", List.of("a.md"));

        assertThatThrownBy(() -> tool.invoke(params, ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("'target' is required");
    }

    @Test
    void missingSourceDocumentSurfacesAsToolException() {
        when(documentService.findByPath(TENANT, PROJECT, "ghost.md"))
                .thenReturn(Optional.empty());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sources", List.of("ghost.md"));
        params.put("target", "out.md");

        assertThatThrownBy(() -> tool.invoke(params, ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("ghost.md")
                .hasMessageContaining("not found");
    }

    // ──────────────── helpers ────────────────

    private void stubInline(String path, String text) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getPath()).thenReturn(path);
        when(doc.getInlineText()).thenReturn(text);
        when(doc.getTenantId()).thenReturn(TENANT);
        when(documentService.findByPath(TENANT, PROJECT, path))
                .thenReturn(Optional.of(doc));
    }

    private DocumentDocument stubCreate(String targetPath, String expectedContent) {
        DocumentDocument created = mock(DocumentDocument.class);
        when(created.getId()).thenReturn("created-id");
        when(created.getProjectId()).thenReturn(PROJECT);
        when(created.getPath()).thenReturn(targetPath);
        when(created.getSize()).thenReturn((long) expectedContent.length());
        when(documentService.createText(
                eq(TENANT), eq(PROJECT), eq(targetPath),
                any(), any(), eq(expectedContent), eq(USER)))
                .thenReturn(created);
        return created;
    }
}
