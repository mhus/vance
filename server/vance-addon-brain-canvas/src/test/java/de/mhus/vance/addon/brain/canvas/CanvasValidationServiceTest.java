package de.mhus.vance.addon.brain.canvas;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.addon.brain.canvas.CanvasValidationService.Finding;
import de.mhus.vance.addon.brain.canvas.CanvasValidationService.Level;
import de.mhus.vance.addon.brain.canvas.model.CanvasDocument;
import de.mhus.vance.addon.brain.canvas.model.CanvasEdge;
import de.mhus.vance.addon.brain.canvas.model.CanvasGraph;
import de.mhus.vance.addon.brain.canvas.model.CanvasNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanvasValidationServiceTest {

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

    private static CanvasDocument doc(List<CanvasNode> nodes, List<CanvasEdge> edges) {
        return new CanvasDocument(null, null, new CanvasGraph(nodes, edges));
    }

    private static long errors(List<Finding> f) {
        return f.stream().filter(x -> x.level() == Level.ERROR).count();
    }

    @Test
    void cleanGraph_hasNoFindings() {
        List<Finding> f = CanvasValidationService.validateGraph(doc(
                List.of(group("g1", null), text("n1", "g1", "a"), text("n2", null, "b")),
                List.of(edge("e1", "n1", "n2"))));
        assertThat(f).isEmpty();
    }

    @Test
    void danglingEdge_isError() {
        List<Finding> f = CanvasValidationService.validateGraph(doc(
                List.of(text("n1", null, "a")),
                List.of(edge("e1", "n1", "n2"))));
        assertThat(errors(f)).isEqualTo(1);
        assertThat(f.get(0).message()).contains("unknown target node 'n2'");
    }

    @Test
    void duplicateNodeId_isError() {
        List<Finding> f = CanvasValidationService.validateGraph(doc(
                List.of(text("n1", null, "a"), text("n1", null, "b")), List.of()));
        assertThat(errors(f)).isEqualTo(1);
        assertThat(f.get(0).message()).contains("Duplicate node id 'n1'");
    }

    @Test
    void parentNotAGroup_isError() {
        List<Finding> f = CanvasValidationService.validateGraph(doc(
                List.of(text("n1", null, "a"), text("n2", "n1", "b")), List.of()));
        assertThat(errors(f)).isEqualTo(1);
        assertThat(f.get(0).message()).contains("is not a group");
    }

    @Test
    void selfParent_isError() {
        List<Finding> f = CanvasValidationService.validateGraph(doc(
                List.of(text("n1", "n1", "a")), List.of()));
        assertThat(errors(f)).isEqualTo(1);
        assertThat(f.get(0).message()).contains("its own parent");
    }

    @Test
    void emptyText_isWarning() {
        List<Finding> f = CanvasValidationService.validateGraph(doc(
                List.of(text("n1", null, "")), List.of()));
        assertThat(errors(f)).isZero();
        assertThat(f).hasSize(1);
        assertThat(f.get(0).level()).isEqualTo(Level.WARNING);
    }
}
