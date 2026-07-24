package de.mhus.vance.addon.brain.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.addon.brain.finance.model.Period;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.model.ValueMode;
import de.mhus.vance.toolpack.ToolException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FinanceTreeOpsTest {

    private static FinanceNode node(String name) {
        return new FinanceNode(name, null, null, null, 1, null, null, List.of(), List.of());
    }

    private static FinanceTreeDocument empty() {
        return FinanceTreeDocument.empty("Plan", null);
    }

    @Test
    void addChild_noParent_setsRoot() {
        FinanceTreeDocument doc = FinanceTreeOps.addChild(empty(), null, node("projekt"));
        assertThat(doc.root()).isNotNull();
        assertThat(doc.root().name()).isEqualTo("projekt");
    }

    @Test
    void addChild_secondRoot_throws() {
        FinanceTreeDocument doc = FinanceTreeOps.addChild(empty(), null, node("projekt"));
        assertThatThrownBy(() -> FinanceTreeOps.addChild(doc, null, node("other")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Root already exists");
    }

    @Test
    void addChild_underParent_appendsChild() {
        FinanceTreeDocument doc = FinanceTreeOps.addChild(empty(), null, node("projekt"));
        doc = FinanceTreeOps.addChild(doc, "projekt", node("einnahmen"));
        assertThat(doc.root().children()).extracting(FinanceNode::name).containsExactly("einnahmen");
    }

    @Test
    void addChild_duplicateName_throws() {
        FinanceTreeDocument doc = FinanceTreeOps.addChild(empty(), null, node("projekt"));
        doc = FinanceTreeOps.addChild(doc, "projekt", node("einnahmen"));
        FinanceTreeDocument fixed = doc;
        assertThatThrownBy(() -> FinanceTreeOps.addChild(fixed, "projekt", node("einnahmen")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void addChild_unknownParent_throws() {
        FinanceTreeDocument doc = FinanceTreeOps.addChild(empty(), null, node("projekt"));
        FinanceTreeDocument fixed = doc;
        assertThatThrownBy(() -> FinanceTreeOps.addChild(fixed, "nope", node("x")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("No node named 'nope'");
    }

    @Test
    void updateNode_changesDisplayFieldsKeepsChildren() {
        FinanceTreeDocument doc = FinanceTreeOps.addChild(empty(), null, node("projekt"));
        doc = FinanceTreeOps.addChild(doc, "projekt", node("ausgaben"));
        doc = FinanceTreeOps.updateNode(doc, "ausgaben",
                Map.of("title", "Ausgaben", "sign", -1));

        FinanceNode ausgaben = FinanceTreeOps.find(doc, "ausgaben");
        assertThat(ausgaben.title()).isEqualTo("Ausgaben");
        assertThat(ausgaben.sign()).isEqualTo(-1);
        assertThat(doc.root().children()).hasSize(1);
    }

    @Test
    void removeNode_dropsSubtree() {
        FinanceTreeDocument doc = FinanceTreeOps.addChild(empty(), null, node("projekt"));
        doc = FinanceTreeOps.addChild(doc, "projekt", node("einnahmen"));
        doc = FinanceTreeOps.addChild(doc, "projekt", node("ausgaben"));
        doc = FinanceTreeOps.removeNode(doc, "einnahmen");
        assertThat(doc.root().children()).extracting(FinanceNode::name).containsExactly("ausgaben");
    }

    @Test
    void removeNode_root_clearsTree() {
        FinanceTreeDocument doc = FinanceTreeOps.addChild(empty(), null, node("projekt"));
        doc = FinanceTreeOps.removeNode(doc, "projekt");
        assertThat(doc.root()).isNull();
    }

    @Test
    void setValues_replacesNodeValues() {
        FinanceTreeDocument doc = FinanceTreeOps.addChild(empty(), null, node("projekt"));
        FinanceValue v = new FinanceValue(800, ValueMode.RECURRING,
                new Period(1, PeriodUnit.MONTH), null, null, null, null);
        doc = FinanceTreeOps.setValues(doc, "projekt", List.of(v));
        assertThat(FinanceTreeOps.find(doc, "projekt").values()).hasSize(1);
        assertThat(FinanceTreeOps.find(doc, "projekt").values().get(0).value()).isEqualTo(800.0);
    }

    @Test
    void setValues_unknownNode_throws() {
        FinanceTreeDocument doc = FinanceTreeOps.addChild(empty(), null, node("projekt"));
        FinanceTreeDocument fixed = doc;
        assertThatThrownBy(() -> FinanceTreeOps.setValues(fixed, "nope", List.of()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("No node named 'nope'");
    }
}
