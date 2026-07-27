package de.mhus.vance.addon.brain.rlang;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure helpers on {@link RExecutionService}. The Rserve roundtrip itself is
 * opt-in integration territory (needs a live daemon) and lives elsewhere.
 */
class RExecutionServiceTest {

    @Test
    void combine_bothEmpty_returnsEmpty() {
        assertThat(RExecutionService.combine("", "")).isEmpty();
        assertThat(RExecutionService.combine(null, null)).isEmpty();
        assertThat(RExecutionService.combine("  ", "\n")).isEmpty();
    }

    @Test
    void combine_outputOnly_returnsOutput() {
        assertThat(RExecutionService.combine("printed line", ""))
                .isEqualTo("printed line");
    }

    @Test
    void combine_valueOnly_returnsValue() {
        assertThat(RExecutionService.combine("", "[1] 42"))
                .isEqualTo("[1] 42");
    }

    @Test
    void combine_bothPresent_joinedWithNewline() {
        assertThat(RExecutionService.combine("hello world", "[1] 3.14"))
                .isEqualTo("hello world\n[1] 3.14");
    }

    @Test
    void combine_stripsBoundaryWhitespace() {
        assertThat(RExecutionService.combine("  hello\n", "\n[1] 42\n"))
                .isEqualTo("hello\n[1] 42");
    }
}
