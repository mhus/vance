package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ThinkingLevelTest {

    @Test
    void fromString_caseInsensitive() {
        assertThat(ThinkingLevel.fromString("HIGH")).contains(ThinkingLevel.HIGH);
        assertThat(ThinkingLevel.fromString("high")).contains(ThinkingLevel.HIGH);
        assertThat(ThinkingLevel.fromString("High")).contains(ThinkingLevel.HIGH);
        assertThat(ThinkingLevel.fromString("  medium  ")).contains(ThinkingLevel.MEDIUM);
    }

    @Test
    void fromString_acceptsThinkingPrefix() {
        assertThat(ThinkingLevel.fromString("thinking_low")).contains(ThinkingLevel.LOW);
        assertThat(ThinkingLevel.fromString("THINKING_HIGH")).contains(ThinkingLevel.HIGH);
    }

    @Test
    void fromString_emptyOrNull_returnsEmpty() {
        assertThat(ThinkingLevel.fromString(null)).isEmpty();
        assertThat(ThinkingLevel.fromString("")).isEmpty();
        assertThat(ThinkingLevel.fromString("   ")).isEmpty();
    }

    @Test
    void fromString_unknownValue_returnsEmpty() {
        assertThat(ThinkingLevel.fromString("ultra")).isEmpty();
        assertThat(ThinkingLevel.fromString("yes")).isEmpty();
    }

    @Test
    void wireName_isLowerCase() {
        assertThat(ThinkingLevel.MINIMAL.wireName()).isEqualTo("minimal");
        assertThat(ThinkingLevel.LOW.wireName()).isEqualTo("low");
        assertThat(ThinkingLevel.MEDIUM.wireName()).isEqualTo("medium");
        assertThat(ThinkingLevel.HIGH.wireName()).isEqualTo("high");
    }
}
