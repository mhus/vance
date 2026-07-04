package de.mhus.vance.addon.brain.canvas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.addon.brain.canvas.model.CanvasDocument;
import de.mhus.vance.addon.brain.canvas.model.CanvasEdge;
import de.mhus.vance.addon.brain.canvas.model.CanvasGraph;
import de.mhus.vance.addon.brain.canvas.model.CanvasNode;
import de.mhus.vance.shared.document.kind.KindCodecException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanvasCodecTest {

    private static final String YAML = "application/yaml";
    private static final String JSON = "application/json";

    private static CanvasDocument sample() {
        CanvasNode g = new CanvasNode.Group("g1", 0, 0, 600, 200, "2", null, "Cluster");
        CanvasNode t = new CanvasNode.Text("n1", 120, 80, 240, 120, "1", null, "Kernidee",
                true, null, "l");
        CanvasNode d = new CanvasNode.Doc("n2", 420, 80, 320, 160, null, null,
                "vance:/assets/x.png?kind=image");
        CanvasNode l = new CanvasNode.Link("n3", 120, 300, 260, 90, null, 5,
                "https://example.com", "Example");
        CanvasEdge e = new CanvasEdge("e1", "n1", "n2",
                CanvasEdge.Side.RIGHT, CanvasEdge.Side.LEFT,
                CanvasEdge.End.NONE, CanvasEdge.End.ARROW, "belegt durch", null);
        return new CanvasDocument("Skizze", "desc",
                new CanvasGraph(List.of(g, t, d, l), List.of(e)));
    }

    @Test
    void yamlRoundTrip_preservesAllNodeTypesAndEdge() {
        CanvasDocument original = sample();
        String yaml = CanvasCodec.serialize(original, YAML);
        CanvasDocument back = CanvasCodec.parse(yaml, YAML);

        assertThat(back.title()).isEqualTo("Skizze");
        assertThat(back.graph().nodes()).hasSize(4);
        assertThat(back.graph().nodes()).extracting(CanvasNode::type)
                .containsExactly("group", "text", "doc", "link");
        assertThat(back.graph().edges()).hasSize(1);
        assertThat(back.graph().edges().get(0).toEnd()).isEqualTo(CanvasEdge.End.ARROW);
    }

    @Test
    void jsonRoundTrip_matchesYamlModel() {
        CanvasDocument original = sample();
        CanvasDocument back = CanvasCodec.parse(CanvasCodec.serialize(original, JSON), JSON);
        assertThat(back.graph().nodes()).hasSize(4);
        CanvasNode.Link link = (CanvasNode.Link) back.graph().nodes().get(3);
        assertThat(link.href()).isEqualTo("https://example.com");
        assertThat(link.z()).isEqualTo(5);
    }

    @Test
    void serialize_omitsDefaultArrowEnds() {
        CanvasEdge dflt = new CanvasEdge("e1", "n1", "n2", null, null,
                CanvasEdge.End.NONE, CanvasEdge.End.ARROW, null, null);
        Map<String, Object> m = CanvasCodec.edgeToMap(dflt);
        assertThat(m).doesNotContainKeys("fromEnd", "toEnd");
    }

    @Test
    void edgeFromMap_defaultsToDirectedArrow() {
        CanvasEdge e = CanvasCodec.edgeFromMap(Map.of("id", "e1", "from", "n1", "to", "n2"));
        assertThat(e.fromEnd()).isEqualTo(CanvasEdge.End.NONE);
        assertThat(e.toEnd()).isEqualTo(CanvasEdge.End.ARROW);
    }

    @Test
    void nodeFromMap_coercesIntegerCoordinatesAndAppliesSizeDefaults() {
        CanvasNode n = CanvasCodec.nodeFromMap(Map.of("id", "n1", "type", "text", "x", 10, "y", 20));
        assertThat(n.x()).isEqualTo(10.0);
        assertThat(n.w()).isEqualTo(240.0); // default
        assertThat(n.h()).isEqualTo(120.0); // default
    }

    @Test
    void nodeFromMap_unknownType_throws() {
        assertThatThrownBy(() -> CanvasCodec.nodeFromMap(Map.of("id", "n1", "type", "sticky")))
                .isInstanceOf(KindCodecException.class);
    }

    @Test
    void nodeFromMap_docWithoutRef_throws() {
        assertThatThrownBy(() -> CanvasCodec.nodeFromMap(Map.of("id", "n1", "type", "doc")))
                .isInstanceOf(KindCodecException.class);
    }

    @Test
    void edgeFromMap_missingEndpoints_throws() {
        assertThatThrownBy(() -> CanvasCodec.edgeFromMap(Map.of("id", "e1", "from", "n1")))
                .isInstanceOf(KindCodecException.class);
    }

    @Test
    void markdownMime_isUnsupported() {
        assertThatThrownBy(() -> CanvasCodec.parse("# hi", "text/markdown"))
                .isInstanceOf(KindCodecException.class);
    }
}
