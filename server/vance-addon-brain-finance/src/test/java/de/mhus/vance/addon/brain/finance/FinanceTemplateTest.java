package de.mhus.vance.addon.brain.finance;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import org.junit.jupiter.api.Test;

/**
 * Guards the bundled {@code _vance/templates/finance-tree.tmpl.yaml} starter
 * structure: the rendered body (Pebble vars substituted) must parse into a
 * valid finance-tree with a projekt root over einnahmen(+)/ausgaben(-).
 */
class FinanceTemplateTest {

    // The template body with {{ title }} / {% if description %} resolved,
    // as PromptTemplateRenderer would emit it for a created document.
    private static final String RENDERED = """
            # vance finance-tree v1 · amounts are per record; a node's sign flips its whole subtree
            # agent: run manual_read('finance-tree') before interpreting · computed values under $computed
            $meta:
              kind: finance-tree
            version: 1
            title: "Q1 Plan"
            description: "desc"
            root:
              name: projekt
              title: "Q1 Plan"
              sign: 1
              children:
                - name: einnahmen
                  title: Einnahmen
                  icon: "💰"
                  sign: 1
                  values: []
                  children: []
                - name: ausgaben
                  title: Ausgaben
                  icon: "🧾"
                  sign: -1
                  values: []
                  children: []
            """;

    @Test
    void starterBody_parsesToValidTree() {
        FinanceTreeDocument doc = FinanceTreeCodec.parse(RENDERED, "application/yaml");

        assertThat(doc.version()).isEqualTo(1);
        assertThat(doc.title()).isEqualTo("Q1 Plan");
        assertThat(doc.root()).isNotNull();
        assertThat(doc.root().name()).isEqualTo("projekt");
        assertThat(doc.root().children()).extracting(FinanceNode::name)
                .containsExactly("einnahmen", "ausgaben");
        assertThat(doc.root().children().get(1).sign()).isEqualTo(-1);
    }
}
