package de.mhus.vance.brain.vault;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScriptSecretAccumulatorTest {

    @AfterEach
    void clean() {
        ScriptSecretAccumulator.evict("r1");
        ScriptSecretAccumulator.evict("r2");
    }

    @Test
    void record_thenPeek_returnsAllValues() {
        ScriptSecretAccumulator.record("r1", "s3cr3t");
        ScriptSecretAccumulator.record("r1", "other-value");

        assertThat(ScriptSecretAccumulator.peek("r1"))
                .containsExactlyInAnyOrder("s3cr3t", "other-value");
    }

    @Test
    void peek_unknownRun_isEmpty() {
        assertThat(ScriptSecretAccumulator.peek("nope")).isEmpty();
    }

    @Test
    void evict_dropsTheRun() {
        ScriptSecretAccumulator.record("r2", "value");
        ScriptSecretAccumulator.evict("r2");
        assertThat(ScriptSecretAccumulator.peek("r2")).isEmpty();
    }

    @Test
    void record_ignoresBlankRunAndEmptyValue() {
        ScriptSecretAccumulator.record("", "value");
        ScriptSecretAccumulator.record("r1", "");
        ScriptSecretAccumulator.record("r1", null);
        assertThat(ScriptSecretAccumulator.peek("r1")).isEmpty();
    }
}
