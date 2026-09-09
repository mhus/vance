package de.mhus.vance.brain.benjy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Structural item caps (decision #23): the schema's {@code initialItems} and
 * {@code split}/{@code revise} item arrays are unbounded — the model *could*
 * return a full upfront plan. The cap makes the anti-Marvin invariant a
 * number instead of a prompt: only a bounded batch of items is committed
 * before facts arrive, the rest comes back via reflect-gaps → route-split.
 */
class BenjyItemCapTest {

    @Test
    void capItems_underCap_returnsAll() {
        List<String> items = List.of("a", "b", "c");
        assertThat(BenjyEngine.capItems(items, 5)).containsExactlyElementsOf(items);
    }

    @Test
    void capItems_exactlyAtCap_returnsAll() {
        List<String> items = List.of("a", "b", "c", "d", "e");
        assertThat(BenjyEngine.capItems(items, 5)).containsExactlyElementsOf(items);
    }

    @Test
    void capItems_overCap_truncatesToCap() {
        List<String> items = List.of("a", "b", "c", "d", "e", "f", "g");
        assertThat(BenjyEngine.capItems(items, 5)).containsExactly("a", "b", "c", "d", "e");
    }

    @Test
    void capItems_capBelowOne_degradesOpen() {
        // 0 / negative = "no cap" — a misconfigured recipe must not silently
        // drop work items; the other safety nets still hold.
        List<String> items = List.of("a", "b", "c");
        assertThat(BenjyEngine.capItems(items, 0)).containsExactlyElementsOf(items);
        assertThat(BenjyEngine.capItems(items, -1)).containsExactlyElementsOf(items);
    }

    @Test
    void capItems_emptyList_staysEmpty() {
        assertThat(BenjyEngine.capItems(List.of(), 5)).isEmpty();
    }

    @Test
    void capNotice_namesSourceAndBothCounts() {
        String notice = BenjyEngine.capNotice("interpret", 15, 5);
        assertThat(notice).contains("interpret").contains("15").contains("5").contains("reflect-gaps");
    }
}
