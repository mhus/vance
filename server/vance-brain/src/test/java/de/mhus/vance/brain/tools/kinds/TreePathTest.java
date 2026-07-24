package de.mhus.vance.brain.tools.kinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.shared.document.kind.TreeDocument;
import de.mhus.vance.shared.document.kind.TreeItem;
import de.mhus.vance.toolpack.ToolException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Integer-path navigation over a {@code kind: tree} document for the
 * {@code tree_*} mutation tools. A wrong index or off-by-one here edits or
 * deletes the wrong node — so parse, resolution, replace/remove/insert, and
 * the out-of-range failure modes are pinned. The mutators are structural (they
 * return a new immutable {@link TreeDocument}); tests assert the untouched
 * branches are preserved.
 */
class TreePathTest {

    /**
     * <pre>
     * 0 a
     *   0 a0
     *   1 a1
     * 1 b
     * </pre>
     */
    private static TreeDocument sample() {
        TreeItem a = new TreeItem("a", List.of(TreeItem.leaf("a0"), TreeItem.leaf("a1")),
                new java.util.LinkedHashMap<>());
        return new TreeDocument("tree", List.of(a, TreeItem.leaf("b")), new java.util.LinkedHashMap<>());
    }

    // ──────────────── parse / format ────────────────

    @Test
    void parse_emptyIsRoot_andReadsIndices() {
        assertThat(TreePath.parse("")).isEmpty();
        assertThat(TreePath.parse("  ")).isEmpty();
        assertThat(TreePath.parse("0,2,1")).containsExactly(0, 2, 1);
        assertThat(TreePath.parse(" 0 , 1 ")).containsExactly(0, 1); // trims
    }

    @Test
    void parse_negativeOrNonNumeric_throws() {
        assertThatThrownBy(() -> TreePath.parse("0,-1"))
                .isInstanceOf(ToolException.class).hasMessageContaining("Negative");
        assertThatThrownBy(() -> TreePath.parse("0,x"))
                .isInstanceOf(ToolException.class).hasMessageContaining("Invalid path segment");
    }

    @Test
    void format_roundTrips() {
        assertThat(TreePath.format(new int[0])).isEmpty();
        assertThat(TreePath.format(new int[] {0, 2, 1})).isEqualTo("0,2,1");
        assertThat(TreePath.parse(TreePath.format(new int[] {3, 1}))).containsExactly(3, 1);
    }

    // ──────────────── resolution ────────────────

    @Test
    void at_resolvesNestedItem() {
        assertThat(TreePath.at(sample(), new int[] {0, 1}).text()).isEqualTo("a1");
        assertThat(TreePath.at(sample(), new int[] {1}).text()).isEqualTo("b");
    }

    @Test
    void at_emptyPathOrOutOfRange_throws() {
        assertThatThrownBy(() -> TreePath.at(sample(), new int[0]))
                .isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> TreePath.at(sample(), new int[] {5}))
                .isInstanceOf(ToolException.class).hasMessageContaining("out of range");
        assertThatThrownBy(() -> TreePath.at(sample(), new int[] {0, 9}))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void itemAt_swallowsOutOfRangeToNull() {
        assertThat(TreePath.itemAt(sample(), new int[] {0, 1}).text()).isEqualTo("a1");
        assertThat(TreePath.itemAt(sample(), new int[] {9})).isNull();
    }

    @Test
    void parentList_returnsContainingList() {
        assertThat(TreePath.parentList(sample(), new int[] {0, 1})).hasSize(2); // a's children
        assertThat(TreePath.parentList(sample(), new int[] {0})).hasSize(2);    // root list
    }

    // ──────────────── findByText ────────────────

    @Test
    void findByText_isCaseInsensitive_andReturnsPaths() {
        List<int[]> hits = TreePath.findByText(sample(), "A1");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0)).containsExactly(0, 1);

        assertThat(TreePath.findByText(sample(), "zzz")).isEmpty();
    }

    // ──────────────── replaceAt ────────────────

    @Test
    void replaceAt_swapsLeaf_preservingSiblings() {
        TreeDocument out = TreePath.replaceAt(sample(), new int[] {0, 0},
                item -> TreeItem.leaf("A0!"));

        assertThat(TreePath.at(out, new int[] {0, 0}).text()).isEqualTo("A0!");
        assertThat(TreePath.at(out, new int[] {0, 1}).text()).isEqualTo("a1"); // sibling intact
        assertThat(TreePath.at(out, new int[] {1}).text()).isEqualTo("b");
    }

    @Test
    void replaceAt_outOfRange_throws() {
        assertThatThrownBy(() -> TreePath.replaceAt(sample(), new int[] {0, 9},
                item -> TreeItem.leaf("x")))
                .isInstanceOf(ToolException.class).hasMessageContaining("out of range");
    }

    // ──────────────── removeAt ────────────────

    @Test
    void removeAt_dropsNestedItem() {
        TreeDocument out = TreePath.removeAt(sample(), new int[] {0, 0});
        assertThat(TreePath.at(out, new int[] {0}).children()).hasSize(1);
        assertThat(TreePath.at(out, new int[] {0, 0}).text()).isEqualTo("a1"); // a1 shifted up
    }

    @Test
    void removeAt_topLevel() {
        TreeDocument out = TreePath.removeAt(sample(), new int[] {0});
        assertThat(out.items()).hasSize(1);
        assertThat(out.items().get(0).text()).isEqualTo("b");
    }

    // ──────────────── insertChild ────────────────

    @Test
    void insertChild_intoRoot_atPositionAndAppend() {
        TreeDocument appended = TreePath.insertChild(sample(), new int[0], -1, TreeItem.leaf("c"));
        assertThat(appended.items()).hasSize(3);
        assertThat(appended.items().get(2).text()).isEqualTo("c");

        TreeDocument atFront = TreePath.insertChild(sample(), new int[0], 0, TreeItem.leaf("z"));
        assertThat(atFront.items().get(0).text()).isEqualTo("z");
    }

    @Test
    void insertChild_intoNestedParent() {
        TreeDocument out = TreePath.insertChild(sample(), new int[] {0}, -1, TreeItem.leaf("a2"));
        assertThat(TreePath.at(out, new int[] {0}).children()).hasSize(3);
        assertThat(TreePath.at(out, new int[] {0, 2}).text()).isEqualTo("a2");
    }
}
