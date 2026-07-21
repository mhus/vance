package de.mhus.vance.addon.brain.canvas;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.addon.brain.canvas.model.CanvasDocument;
import de.mhus.vance.addon.brain.canvas.model.CanvasEdge;
import de.mhus.vance.addon.brain.canvas.model.CanvasGraph;
import de.mhus.vance.addon.brain.canvas.model.CanvasNode;
import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The handler is a thin adapter — the graph checks themselves live in
 * {@link CanvasValidationServiceTest}. Here we prove the full path through the
 * real codec: serialize a canvas → {@code validate(content)} parses it, runs
 * {@code validateGraph}, and maps its findings onto the shared vocabulary.
 */
class CanvasKindHandlerTest {

    private static final String YAML = "application/yaml";
    private final CanvasKindHandler handler = new CanvasKindHandler();

    private static CanvasNode.Text text(String id, String parent, String txt) {
        return new CanvasNode.Text(id, 0, 0, 10, 10, null, null, parent, txt,
                null, null, null, null, null);
    }

    private static CanvasNode.Group group(String id, String parent) {
        return new CanvasNode.Group(id, 0, 0, 100, 100, null, null, parent, "G");
    }

    private static CanvasEdge edge(String id, String from, String to) {
        return new CanvasEdge(id, from, to, null, null,
                CanvasEdge.End.NONE, CanvasEdge.End.ARROW, null, null, null, null);
    }

    private static String yaml(List<CanvasNode> nodes, List<CanvasEdge> edges) {
        return CanvasCodec.serialize(
                new CanvasDocument(null, null, new CanvasGraph(nodes, edges)), YAML);
    }

    private static KindValidationContext ctx() {
        return new KindValidationContext("t", "p", "board.canvas.yaml", YAML, NO_REFS);
    }

    private static final DocRefs NO_REFS = new DocRefs() {
        @Override public boolean exists(String path) { return false; }
        @Override public @Nullable String kindOf(String path) { return null; }
        @Override public @Nullable Map<String, Object> readYaml(String path) { return null; }
    };

    @Test
    void cleanCanvas_hasNoFindings() {
        String body = yaml(
                List.of(group("g1", null), text("n1", "g1", "a"), text("n2", null, "b")),
                List.of(edge("e1", "n1", "n2")));
        assertThat(handler.validate(body, ctx())).isEmpty();
    }

    @Test
    void danglingEdge_mapsToSharedError() {
        String body = yaml(List.of(text("n1", null, "a")), List.of(edge("e1", "n1", "n2")));
        List<Finding> findings = handler.validate(body, ctx());
        assertThat(findings).anyMatch(f ->
                f.level() == Finding.Level.ERROR
                        && f.code().equals("canvas-graph")
                        && f.message().contains("unknown target node 'n2'"));
    }

    @Test
    void unparseableContent_isCanvasParseError() {
        List<Finding> findings = handler.validate("\t : : not a canvas : [", ctx());
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.ERROR);
        assertThat(findings.get(0).code()).isEqualTo("canvas-parse");
    }
}
