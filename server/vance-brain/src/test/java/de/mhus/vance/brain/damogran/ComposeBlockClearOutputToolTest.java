package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ComposeBlockClearOutputToolTest {

    private KindToolSupport support;
    private ComposeBlockClearOutputTool tool;
    private DocumentDocument doc;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setup() {
        support = mock(KindToolSupport.class);
        tool = new ComposeBlockClearOutputTool(support);
        doc = mock(DocumentDocument.class);
        ctx = mock(ToolInvocationContext.class);
        when(doc.getId()).thenReturn("doc-1");
        when(doc.getPath()).thenReturn("notes/x.compose.yaml");
        when(support.loadDocument(any(), any())).thenReturn(doc);
    }

    @Test
    void clear_removesManagedBlock_writesAndReportsCleared() {
        when(support.readBody(doc, ctx)).thenReturn(
                "name: demo\n\n# generated — compose run state (do not edit)\n$output:\n  - path: a\n    uri: u\n");

        Map<String, Object> out = tool.invoke(Map.of(), ctx);

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(support).writeBody(any(), written.capture(), any());
        assertThat(written.getValue()).isEqualTo("name: demo\n");
        assertThat(out).containsEntry("cleared", true).containsEntry("documentId", "doc-1");
    }

    @Test
    void clear_noManagedBlock_isNoOp() {
        when(support.readBody(doc, ctx)).thenReturn("name: demo\n");

        Map<String, Object> out = tool.invoke(Map.of(), ctx);

        verify(support, never()).writeBody(any(), any(), any());
        assertThat(out).containsEntry("cleared", false);
    }
}
