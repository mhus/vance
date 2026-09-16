package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocHeadTailToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj-a", "sess", "proc", "user", null);

    private KindToolSupport support;
    private DocHeadTailTool tool;
    private DocumentDocument doc;

    @BeforeEach
    void setUp() {
        support = mock(KindToolSupport.class);
        tool = new DocHeadTailTool(support);
        doc = mock(DocumentDocument.class);
        when(doc.getId()).thenReturn("doc-1");
        when(doc.getPath()).thenReturn("notes/log.md");
        when(support.loadDocument(any(), any())).thenReturn(doc);
    }

    private void bodyIs(String body) {
        when(support.readBody(doc, CTX)).thenReturn(body);
    }

    private Map<String, Object> params(Integer head, Integer tail) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", "doc-1");
        if (head != null) p.put("head", head);
        if (tail != null) p.put("tail", tail);
        return p;
    }

    @Test
    @SuppressWarnings("unchecked")
    void headAndTail_returnBoundedSlicesWithLineNumbers() {
        bodyIs("l1\nl2\nl3\nl4\nl5");

        Map<String, Object> out = tool.invoke(params(2, 1), CTX);

        assertThat(out.get("totalLines")).isEqualTo(5);
        var head = (java.util.List<Map<String, Object>>) out.get("head");
        var tail = (java.util.List<Map<String, Object>>) out.get("tail");
        assertThat(head).extracting(r -> r.get("lineNumber")).containsExactly(1, 2);
        assertThat(tail).extracting(r -> r.get("lineNumber")).containsExactly(5);
        assertThat(tail.get(0)).containsEntry("line", "l5");
    }

    @Test
    void headOnly_onShortDoc_clampsToTotal() {
        bodyIs("only line");

        Map<String, Object> out = tool.invoke(params(10, null), CTX);

        assertThat(out.get("totalLines")).isEqualTo(1);
        assertThat(out).doesNotContainKey("tail");
        assertThat(out.get("head"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(1);
    }

    @Test
    void neitherHeadNorTail_isRejected() {
        bodyIs("body");

        assertThatThrownBy(() -> tool.invoke(params(null, null), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("At least one of 'head' or 'tail' must be > 0");
    }

    @Test
    void oversizedRequest_isClampedNotRejected() {
        bodyIs("a\nb");

        Map<String, Object> out = tool.invoke(params(100_000, null), CTX);

        // Clamped silently to MAX_LINES — the cap bounds the slice, and a
        // bigger request on a small doc simply returns everything.
        assertThat(out.get("totalLines")).isEqualTo(2);
    }
}
