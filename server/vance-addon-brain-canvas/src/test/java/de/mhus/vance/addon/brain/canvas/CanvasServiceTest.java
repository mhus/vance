package de.mhus.vance.addon.brain.canvas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.canvas.model.CanvasDocument;
import de.mhus.vance.addon.brain.canvas.model.CanvasEdge;
import de.mhus.vance.addon.brain.canvas.model.CanvasGraph;
import de.mhus.vance.addon.brain.canvas.model.CanvasNode;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CanvasServiceTest {

    private static final String YAML = "application/yaml";

    private DocumentService documentService;
    private CanvasService service;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        service = new CanvasService(documentService);
    }

    private DocumentDocument docWithBody(String body) {
        DocumentDocument doc = mock(DocumentDocument.class);
        when(doc.getId()).thenReturn("id1");
        when(doc.getMimeType()).thenReturn(YAML);
        when(doc.getPath()).thenReturn("x.canvas.yaml");
        when(doc.getTitle()).thenReturn("T");
        when(documentService.loadContent(doc))
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(documentService.update(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(doc);
        return doc;
    }

    private String capturedBody() {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(documentService).update(
                eq("id1"), any(), any(), cap.capture(), any(), any(), any(), any(), any());
        return cap.getValue();
    }

    @Test
    void nextId_emptyExisting_returnsPrefixOne() {
        assertThat(CanvasService.nextId(List.of(), "n")).isEqualTo("n1");
    }

    @Test
    void nextId_skipsToMaxPlusOne() {
        assertThat(CanvasService.nextId(List.of("n1", "n5", "other"), "n")).isEqualTo("n6");
    }

    @Test
    void addNode_emptyGraph_mintsN1AndWritesNode() {
        String body = CanvasCodec.serialize(CanvasDocument.empty("T", null), YAML);
        DocumentDocument doc = docWithBody(body);

        CanvasService.MutationResult res =
                service.addNode(doc, Map.of("type", "text", "x", 10, "y", 20, "text", "hi"));

        assertThat(res.id()).isEqualTo("n1");
        CanvasDocument written = CanvasCodec.parse(capturedBody(), YAML);
        assertThat(written.graph().nodes()).hasSize(1);
        assertThat(written.graph().nodes().get(0)).isInstanceOf(CanvasNode.Text.class);
    }

    @Test
    void addNode_duplicateExplicitId_throws() {
        CanvasNode existing = new CanvasNode.Text("n1", 0, 0, 10, 10, null, null, "a", null, null, null);
        String body = CanvasCodec.serialize(
                new CanvasDocument("T", null, new CanvasGraph(List.of(existing), List.of())), YAML);
        DocumentDocument doc = docWithBody(body);

        assertThatThrownBy(() ->
                service.addNode(doc, Map.of("id", "n1", "type", "text", "text", "b")))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void addEdge_missingEndpointNode_throws() {
        String body = CanvasCodec.serialize(CanvasDocument.empty("T", null), YAML);
        DocumentDocument doc = docWithBody(body);

        assertThatThrownBy(() ->
                service.addEdge(doc, Map.of("from", "n1", "to", "n2")))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void deleteNode_dropsIncidentEdges() {
        CanvasNode n1 = new CanvasNode.Text("n1", 0, 0, 10, 10, null, null, "a", null, null, null);
        CanvasNode n2 = new CanvasNode.Text("n2", 20, 0, 10, 10, null, null, "b", null, null, null);
        CanvasEdge e1 = new CanvasEdge("e1", "n1", "n2", null, null,
                CanvasEdge.End.NONE, CanvasEdge.End.ARROW, null, null);
        String body = CanvasCodec.serialize(
                new CanvasDocument("T", null, new CanvasGraph(List.of(n1, n2), List.of(e1))), YAML);
        DocumentDocument doc = docWithBody(body);

        service.deleteNode(doc, "n1");

        CanvasDocument written = CanvasCodec.parse(capturedBody(), YAML);
        assertThat(written.graph().nodes()).extracting(CanvasNode::id).containsExactly("n2");
        assertThat(written.graph().edges()).isEmpty();
    }

    @Test
    void updateNode_patchesPositionKeepsId() {
        CanvasNode n1 = new CanvasNode.Text("n1", 0, 0, 10, 10, null, null, "a", null, null, null);
        String body = CanvasCodec.serialize(
                new CanvasDocument("T", null, new CanvasGraph(List.of(n1), List.of())), YAML);
        DocumentDocument doc = docWithBody(body);

        service.updateNode(doc, "n1", Map.of("x", 99, "id", "hacked"));

        CanvasDocument written = CanvasCodec.parse(capturedBody(), YAML);
        assertThat(written.graph().nodes()).hasSize(1);
        CanvasNode node = written.graph().nodes().get(0);
        assertThat(node.id()).isEqualTo("n1"); // id immutable
        assertThat(node.x()).isEqualTo(99.0);
    }
}
