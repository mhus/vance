package de.mhus.vance.brain.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HookSourceKeysTest {

    @Test
    void sourceFor_concatenatesEventAndName() {
        assertThat(HookSourceKeys.sourceFor("process.completed", "notify"))
                .isEqualTo("hook:process.completed:notify");
    }

    @Test
    void prefix_isStable() {
        assertThat(HookSourceKeys.SOURCE_PREFIX).isEqualTo("hook:");
    }
}
