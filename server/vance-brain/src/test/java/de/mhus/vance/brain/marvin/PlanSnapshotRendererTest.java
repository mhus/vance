package de.mhus.vance.brain.marvin;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.marvin.NodeStatus;
import de.mhus.vance.api.marvin.TaskKind;
import de.mhus.vance.shared.marvin.MarvinNodeDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanSnapshotRendererTest {

    private final PlanSnapshotRenderer renderer = new PlanSnapshotRenderer();

    @Test
    void emptyList_yieldsEmptyTreeMarker() {
        String out = renderer.render(List.of(), null);
        assertThat(out).contains("LIVE PLAN").contains("empty tree");
    }

    @Test
    void rootOnly_rendersRoot() {
        MarvinNodeDocument root = node("r1", null, 0,
                NodeStatus.RUNNING, "Top goal");
        String out = renderer.render(List.of(root), "r1");
        assertThat(out)
                .contains("ROOT")
                .contains("running")
                .contains("Top goal")
                .contains("[YOU ARE HERE]")
                .contains(PlanSnapshotRenderer.OPEN_MARKER)
                .contains(PlanSnapshotRenderer.CLOSE_MARKER);
    }

    @Test
    void rootWithChildren_rendersStructure() {
        MarvinNodeDocument root = node("r1", null, 0,
                NodeStatus.RUNNING, "Atomkraft-Recherche");
        MarvinNodeDocument c1 = nodeWithSummary("c1", "r1", 0,
                NodeStatus.DONE, "Historie",
                "Erste Reaktoren 1942; Tschernobyl 1986");
        MarvinNodeDocument c2 = node("c2", "r1", 1,
                NodeStatus.RUNNING, "Tech-Stand moderner Reaktoren");
        MarvinNodeDocument c3 = node("c3", "r1", 2,
                NodeStatus.PENDING, "Ökologie");
        String out = renderer.render(List.of(root, c1, c2, c3), "c2");
        assertThat(out)
                .contains("Atomkraft-Recherche")
                .contains("#1")
                .contains("Historie")
                .contains("Erste Reaktoren 1942")
                .contains("#2")
                .contains("[YOU ARE HERE]")
                .contains("#3")
                .contains("planned");
    }

    @Test
    void hugeTree_prunesWithSummary() {
        List<MarvinNodeDocument> tree = new ArrayList<>();
        MarvinNodeDocument root = node("r", null, 0,
                NodeStatus.RUNNING, "Big root");
        tree.add(root);
        // Make 30 distant children with long summaries that bust the cap.
        for (int i = 0; i < 30; i++) {
            MarvinNodeDocument k = nodeWithSummary("k" + i, "r", i,
                    NodeStatus.DONE, "Child " + i,
                    "Lorem ipsum dolor sit amet, consectetur adipiscing elit. "
                            + "Sed do eiusmod tempor incididunt ut labore et dolore "
                            + "magna aliqua. Ut enim ad minim veniam, quis nostrud.");
            tree.add(k);
        }
        // Current node is one of the distant ones.
        String out = renderer.render(tree, "k0");
        assertThat(out)
                .contains("LIVE PLAN")
                .contains("YOU ARE HERE")
                // Snapshot is bounded (with some slack for the markers).
                .matches("(?s).{1,5500}");
    }

    @Test
    void currentNodePathStaysFullEvenWhenPruning() {
        List<MarvinNodeDocument> tree = new ArrayList<>();
        MarvinNodeDocument root = node("r", null, 0, NodeStatus.RUNNING, "Root");
        MarvinNodeDocument parent = node("p", "r", 0, NodeStatus.RUNNING, "Parent");
        MarvinNodeDocument here = node("h", "p", 0, NodeStatus.RUNNING, "Here");
        tree.add(root);
        tree.add(parent);
        tree.add(here);
        // Plus enough distant siblings to trigger pruning.
        for (int i = 1; i < 20; i++) {
            tree.add(nodeWithSummary("d" + i, "r", i,
                    NodeStatus.DONE, "Distant " + i,
                    "lorem ".repeat(80)));
        }
        String out = renderer.render(tree, "h");
        assertThat(out)
                .contains("Root")
                .contains("Parent")
                .contains("Here")
                .contains("[YOU ARE HERE]");
    }

    // ─── helpers ───

    private static MarvinNodeDocument node(
            String id, String parentId, int position,
            NodeStatus status, String goal) {
        return MarvinNodeDocument.builder()
                .id(id).parentId(parentId).position(position)
                .status(status).goal(goal).taskKind(TaskKind.WORKER)
                .processId("p").tenantId("t")
                .build();
    }

    private static MarvinNodeDocument nodeWithSummary(
            String id, String parentId, int position,
            NodeStatus status, String goal, String summary) {
        Map<String, Object> art = new LinkedHashMap<>();
        art.put("result", summary);
        return MarvinNodeDocument.builder()
                .id(id).parentId(parentId).position(position)
                .status(status).goal(goal).taskKind(TaskKind.WORKER)
                .processId("p").tenantId("t")
                .artifacts(art)
                .build();
    }
}
