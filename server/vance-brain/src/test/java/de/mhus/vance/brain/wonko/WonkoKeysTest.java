package de.mhus.vance.brain.wonko;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WonkoKeysTest {

    @Test
    void topicKey_mixedShapes_becomeKebabHierarchy() {
        assertThat(WonkoKeys.topicKey("Retry Policy/Back_off ")).isEqualTo("retry-policy/back-off");
        assertThat(WonkoKeys.topicKey("/a//b/")).isEqualTo("a/b");
    }

    @Test
    void slug_dropsHierarchyCharactersAndTrailingDashes() {
        assertThat(WonkoKeys.slug("Data Model!")).isEqualTo("data-model");
        assertThat(WonkoKeys.slug(null)).isEmpty();
    }
}
