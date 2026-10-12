package de.mhus.vance.brain.zem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ZemStateSelectorTest {

    private static final Map<String, Object> RECORD = Map.of(
            "message", "charged twice",
            "customer", Map.of("tier", "gold", "email", "a@b.c", "address", Map.of("country", "DE")),
            "internalNotes", "irrelevant noise");

    @Test
    void keeps_only_the_selected_paths_with_their_nesting() {
        Object selected =
                ZemStateSelector.select(RECORD, List.of("message", "customer.tier", "customer.address.country"));

        assertThat(selected)
                .isEqualTo(Map.of(
                        "message",
                        "charged twice",
                        "customer",
                        Map.of("tier", "gold", "address", Map.of("country", "DE"))));
    }

    @Test
    void missing_paths_are_skipped() {
        assertThat(ZemStateSelector.select(RECORD, List.of("message", "order.id", "message.length")))
                .isEqualTo(Map.of("message", "charged twice"));
    }

    @Test
    void no_selection_or_non_map_state_passes_through() {
        assertThat(ZemStateSelector.select(RECORD, List.of())).isSameAs(RECORD);
        assertThat(ZemStateSelector.select("plain text", List.of("message"))).isEqualTo("plain text");
        List<String> list = List.of("a", "b");
        assertThat(ZemStateSelector.select(list, List.of("message"))).isSameAs(list);
    }
}
