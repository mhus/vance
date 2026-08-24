package de.mhus.vance.addon.brain.bistromath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A view's scalars end up in front of the reader, so how they resolve is
 * visible. These tests pin the cases where SnakeYAML's YAML-1.1 defaults would
 * show something the author did not write — plus the one where an earlier
 * version hid the real mistake.
 */
class BistromathYamlTest {

    private static Map<?, ?> map(String yaml) {
        return (Map<?, ?>) BistromathYaml.load(yaml, "v.yaml");
    }

    @Test
    void load_no_staysAStringInsteadOfBecomingFalse() {
        assertThat(map("status: no\n").get("status")).isEqualTo("no");
    }

    @Test
    void load_onAndOff_stayStrings() {
        Map<?, ?> row = map("a: on\nb: off\n");

        assertThat(row.get("a")).isEqualTo("on");
        assertThat(row.get("b")).isEqualTo("off");
    }

    @Test
    void load_isoDate_staysAStringInsteadOfBecomingADate() {
        assertThat(map("due: 2026-01-01\n").get("due"))
                .isInstanceOf(String.class)
                .isEqualTo("2026-01-01");
    }

    @Test
    void load_trueAndFalse_areStillBooleans() {
        Map<?, ?> row = map("paid: true\nvoid: false\n");

        assertThat(row.get("paid")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("void")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void load_numbers_areStillNumbers() {
        Map<?, ?> row = map("amount: 1200\nrate: 0.19\n");

        assertThat(row.get("amount")).isEqualTo(1200);
        assertThat(row.get("rate")).isEqualTo(0.19);
    }

    /**
     * The version that returned {@code null} here made the parser answer "not a
     * YAML mapping — a view starts with `type: page`", which sends the author
     * to line 1 of a document whose line 1 is fine.
     */
    @Test
    void load_brokenYaml_namesTheDocumentAndWhereItBroke() {
        assertThatThrownBy(() -> BistromathYaml.load("a: [unclosed\n", "views/main.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("views/main.yaml")
                .hasMessageContaining("not valid YAML")
                .hasMessageContaining("line");
    }

    /** Empty is a document, just an empty one — the caller decides about that. */
    @Test
    void load_emptyText_isNullRatherThanAnError() {
        assertThat(BistromathYaml.load("   \n", "v.yaml")).isNull();
    }
}
