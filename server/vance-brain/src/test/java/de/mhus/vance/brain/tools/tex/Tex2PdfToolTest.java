package de.mhus.vance.brain.tools.tex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Tex2PdfTool} — the thin LLM-facing wrapper
 * around {@link TexService}. Tests verify parameter extraction,
 * success/failure return shapes, context validation, and markdown
 * link construction.
 */
class Tex2PdfToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "test-proj", "sess", "proc-1", "user");

    private TexService texService;
    private DocumentService documentService;
    private DocumentLinkBuilder linkBuilder;
    private Tex2PdfTool tool;

    @BeforeEach
    void setUp() {
        texService = mock(TexService.class);
        documentService = mock(DocumentService.class);
        linkBuilder = mock(DocumentLinkBuilder.class);
        tool = new Tex2PdfTool(texService, documentService, linkBuilder);
    }

    // ── tool metadata ─────────────────────────────────────────────

    @Test
    void name_isTex2pdf() {
        assertThat(tool.name()).isEqualTo("tex2pdf");
    }

    @Test
    void primary_isTrue() {
        assertThat(tool.primary()).isTrue();
    }

    @Test
    void labels_containsWrite() {
        assertThat(tool.labels()).contains("write");
    }

    @Test
    void description_isNonEmpty() {
        assertThat(tool.description()).isNotBlank();
    }

    @Test
    void paramsSchema_requiresComposePath() {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = tool.paramsSchema();
        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("composePath");
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) schema.get("required");
        assertThat(required).containsExactly("composePath");
    }

    // ── invoke: success ───────────────────────────────────────────

    @Test
    void invoke_success_returnsSuccessMap() {
        when(texService.compile("acme", "test-proj", "docs/compose.yaml", "proc-1"))
                .thenReturn(TexService.TexCompileResult.success("docs/thesis.pdf", 1500));

        Map<String, Object> out = tool.invoke(Map.of("composePath", "docs/compose.yaml"), CTX);

        assertThat(out).containsEntry("success", true);
        assertThat(out).containsEntry("pdfPath", "docs/thesis.pdf");
        assertThat((long) out.get("elapsedMs")).isEqualTo(1500L);
    }

    @Test
    void invoke_success_buildsMarkdownLink() {
        when(texService.compile(any(), any(), any(), any()))
                .thenReturn(TexService.TexCompileResult.success("docs/thesis.pdf", 1000));
        DocumentDocument pdfDoc = mock(DocumentDocument.class);
        when(documentService.findByPath("acme", "test-proj", "docs/thesis.pdf"))
                .thenReturn(Optional.of(pdfDoc));
        when(linkBuilder.linkFor(pdfDoc, "test-proj")).thenReturn("[thesis.pdf](vance:/docs/thesis.pdf)");

        Map<String, Object> out = tool.invoke(Map.of("composePath", "docs/compose.yaml"), CTX);

        assertThat(out).containsEntry("markdownLink", "[thesis.pdf](vance:/docs/thesis.pdf)");
    }

    @Test
    void invoke_success_fallsBackToVanceLink_whenDocNotFound() {
        when(texService.compile(any(), any(), any(), any()))
                .thenReturn(TexService.TexCompileResult.success("out.pdf", 500));
        when(documentService.findByPath(any(), any(), eq("out.pdf")))
                .thenReturn(Optional.empty());

        Map<String, Object> out = tool.invoke(Map.of("composePath", "compose.yaml"), CTX);

        assertThat(out).containsEntry("markdownLink", "vance:/out.pdf");
    }

    @Test
    void invoke_success_fallsBackToVanceLink_whenLinkBuilderThrows() {
        when(texService.compile(any(), any(), any(), any()))
                .thenReturn(TexService.TexCompileResult.success("out.pdf", 500));
        DocumentDocument pdfDoc = mock(DocumentDocument.class);
        when(documentService.findByPath(any(), any(), eq("out.pdf")))
                .thenReturn(Optional.of(pdfDoc));
        when(linkBuilder.linkFor(any(), any())).thenThrow(new RuntimeException("boom"));

        Map<String, Object> out = tool.invoke(Map.of("composePath", "compose.yaml"), CTX);

        assertThat(out).containsEntry("markdownLink", "vance:/out.pdf");
    }

    // ── invoke: failure ───────────────────────────────────────────

    @Test
    void invoke_failure_returnsErrorMap() {
        when(texService.compile(any(), any(), any(), any()))
                .thenReturn(TexService.TexCompileResult.failure("Compilation failed", "log line 1\nlog line 2", 2000));

        Map<String, Object> out = tool.invoke(Map.of("composePath", "compose.yaml"), CTX);

        assertThat(out).containsEntry("success", false);
        assertThat(out).containsEntry("error", "Compilation failed");
        assertThat(out).containsEntry("logExcerpt", "log line 1\nlog line 2");
        assertThat((long) out.get("elapsedMs")).isEqualTo(2000L);
    }

    @Test
    void invoke_failure_omitsLogExcerptWhenNull() {
        when(texService.compile(any(), any(), any(), any()))
                .thenReturn(TexService.TexCompileResult.failure("Error without log", null, 100));

        Map<String, Object> out = tool.invoke(Map.of("composePath", "compose.yaml"), CTX);

        assertThat(out).containsEntry("success", false);
        assertThat(out).containsEntry("error", "Error without log");
        assertThat(out).doesNotContainKey("logExcerpt");
    }

    // ── invoke: parameter validation ──────────────────────────────

    @Test
    void invoke_throws_whenComposePathMissing() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("composePath");
    }

    @Test
    void invoke_throws_whenComposePathBlank() {
        assertThatThrownBy(() -> tool.invoke(Map.of("composePath", "  "), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("composePath");
    }

    @Test
    void invoke_throws_whenComposePathNotString() {
        assertThatThrownBy(() -> tool.invoke(Map.of("composePath", 123), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("composePath");
    }

    @Test
    void invoke_throws_whenContextNull() {
        assertThatThrownBy(() -> tool.invoke(Map.of("composePath", "x"), null))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("tenant");
    }

    @Test
    void invoke_throws_whenTenantIdBlank() {
        ToolInvocationContext badCtx =
                new ToolInvocationContext("  ", "proj", "s", "p", "u");
        assertThatThrownBy(() -> tool.invoke(Map.of("composePath", "x"), badCtx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("tenant");
    }

    @Test
    void invoke_throws_whenProjectIdNull() {
        ToolInvocationContext badCtx =
                new ToolInvocationContext("acme", null, "s", "p", "u");
        assertThatThrownBy(() -> tool.invoke(Map.of("composePath", "x"), badCtx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("projectId");
    }

    // ── invoke: delegates to service with correct args ───────────

    @Test
    void invoke_delegatesToServiceWithCorrectArgs() {
        when(texService.compile(any(), any(), any(), any()))
                .thenReturn(TexService.TexCompileResult.success("out.pdf", 100));

        tool.invoke(Map.of("composePath", "path/to/compose.yaml"), CTX);

        verify(texService).compile("acme", "test-proj", "path/to/compose.yaml", "proc-1");
    }

    @Test
    void invoke_trimsComposePath() {
        when(texService.compile(any(), any(), any(), any()))
                .thenReturn(TexService.TexCompileResult.success("out.pdf", 100));

        tool.invoke(Map.of("composePath", "  compose.yaml  "), CTX);

        verify(texService).compile("acme", "test-proj", "compose.yaml", "proc-1");
    }
}
