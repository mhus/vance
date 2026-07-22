package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the {@code doc_replace_lines} hardening: out-of-range
 * {@code toLine} fails loud (never clamps), and the optional anchors
 * ({@code expectedFirstLine}/{@code expectedLastLine}) are verified before
 * patching so stale line numbers can't silently clobber the wrong lines.
 */
class DocReplaceLinesToolTest {

    private KindToolSupport support;
    private DocReplaceLinesTool tool;
    private DocumentDocument doc;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setup() {
        support = mock(KindToolSupport.class);
        tool = new DocReplaceLinesTool(support);
        doc = mock(DocumentDocument.class);
        ctx = mock(ToolInvocationContext.class);
        when(doc.getId()).thenReturn("doc-1");
        when(doc.getPath()).thenReturn("notes/x.md");
        when(support.loadDocument(any(), any())).thenReturn(doc);
        when(support.requireInline(doc)).thenReturn(doc);
    }

    private void bodyIs(String body) {
        when(support.readBody(doc, ctx)).thenReturn(body);
    }

    private Map<String, Object> params(int from, int to, String newContent) {
        Map<String, Object> p = new HashMap<>();
        p.put("fromLine", from);
        p.put("toLine", to);
        p.put("newContent", newContent);
        return p;
    }

    @Test
    void replaceLines_middleRange_patchesExactlyThatRange() {
        bodyIs("a\nb\nc\nd\n");

        tool.invoke(params(2, 3, "X"), ctx);

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(support).writeBody(any(), written.capture(), any());
        assertThat(written.getValue()).isEqualTo("a\nX\nd\n");
    }

    @Test
    void toLineBeyondDocument_failsLoud_insteadOfClamping() {
        bodyIs("a\nb\nc");

        assertThatThrownBy(() -> tool.invoke(params(2, 99, "X"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("exceeds document length")
                .hasMessageContaining("stale");

        verify(support, org.mockito.Mockito.never()).writeBody(any(), any(), any());
    }

    @Test
    void expectedFirstLineMismatch_errorsAndDoesNotWrite() {
        bodyIs("alpha\nbeta\ngamma");
        Map<String, Object> p = params(2, 2, "X");
        p.put("expectedFirstLine", "BETA-CHANGED");

        assertThatThrownBy(() -> tool.invoke(p, ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("expectedFirstLine mismatch at line 2");

        verify(support, org.mockito.Mockito.never()).writeBody(any(), any(), any());
    }

    @Test
    void expectedFirstLineMatch_whitespaceInsensitive_proceeds() {
        bodyIs("alpha\n  beta  \ngamma");
        Map<String, Object> p = params(2, 2, "X");
        p.put("expectedFirstLine", "beta");

        tool.invoke(p, ctx);

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(support).writeBody(any(), written.capture(), any());
        assertThat(written.getValue()).isEqualTo("alpha\nX\ngamma");
    }

    @Test
    void expectedLastLineMismatch_errors() {
        bodyIs("alpha\nbeta\ngamma");
        Map<String, Object> p = params(1, 3, "X");
        p.put("expectedLastLine", "not-gamma");

        assertThatThrownBy(() -> tool.invoke(p, ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("expectedLastLine mismatch at line 3");
    }

    @Test
    void insertOnly_ignoresExpectedLastLine_andInsertsAbove() {
        bodyIs("a\nb\nc");
        Map<String, Object> p = params(2, 1, "NEW"); // toLine = fromLine-1 → insert above line 2
        p.put("expectedFirstLine", "b");
        p.put("expectedLastLine", "whatever-ignored");

        tool.invoke(p, ctx);

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(support).writeBody(any(), written.capture(), any());
        assertThat(written.getValue()).isEqualTo("a\nNEW\nb\nc");
    }
}
