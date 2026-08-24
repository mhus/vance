package de.mhus.vance.addon.brain.bistromath;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A row's scalars end up in a table cell, so how they resolve is visible to
 * the reader. These tests pin the two cases where SnakeYAML's YAML-1.1
 * defaults would show something the author did not write.
 */
class BistromathYamlTest {

    @Test
    void load_no_staysAStringInsteadOfBecomingFalse() {
        Map<?, ?> row = BistromathYaml.loadMap("status: no\n");

        assertThat(row.get("status")).isEqualTo("no");
    }

    @Test
    void load_onAndOff_stayStrings() {
        Map<?, ?> row = BistromathYaml.loadMap("a: on\nb: off\n");

        assertThat(row.get("a")).isEqualTo("on");
        assertThat(row.get("b")).isEqualTo("off");
    }

    @Test
    void load_isoDate_staysAStringInsteadOfBecomingADate() {
        Map<?, ?> row = BistromathYaml.loadMap("due: 2026-01-01\n");

        assertThat(row.get("due")).isInstanceOf(String.class).isEqualTo("2026-01-01");
    }

    @Test
    void load_trueAndFalse_areStillBooleans() {
        Map<?, ?> row = BistromathYaml.loadMap("paid: true\nvoid: false\n");

        assertThat(row.get("paid")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("void")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void load_numbers_areStillNumbers() {
        Map<?, ?> row = BistromathYaml.loadMap("amount: 1200\nrate: 0.19\n");

        assertThat(row.get("amount")).isEqualTo(1200);
        assertThat(row.get("rate")).isEqualTo(0.19);
    }

    @Test
    void load_unparseableInput_returnsNullRatherThanThrowing() {
        assertThat(BistromathYaml.load("a: [unclosed\n")).isNull();
    }

    @Test
    void loadMap_nonMappingRoot_isAnEmptyMap() {
        assertThat(BistromathYaml.loadMap("- one\n")).isEmpty();
    }

    @Test
    void stringify_list_readsAsDataNotAsAJavaToString() {
        assertThat(BistromathYaml.stringify(List.of("a", "b"))).isEqualTo("a, b");
    }

    @Test
    void stringify_map_readsAsKeyValuePairs() {
        assertThat(BistromathYaml.stringify(Map.of("k", "v"))).isEqualTo("k: v");
    }

    @Test
    void stringify_null_isEmptyString() {
        assertThat(BistromathYaml.stringify(null)).isEmpty();
    }
}
