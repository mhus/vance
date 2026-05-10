package de.mhus.vance.brain.ai.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.ThinkingLevel;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import org.junit.jupiter.api.Test;

class GeminiProviderThinkingTest {

    @Test
    void mapThinking_off_returnsNull() {
        assertThat(GeminiProvider.mapThinking(ThinkingLevel.OFF)).isNull();
    }

    @Test
    void mapThinking_each_level_emitsConfig() {
        for (ThinkingLevel level : new ThinkingLevel[]{
                ThinkingLevel.MINIMAL, ThinkingLevel.LOW,
                ThinkingLevel.MEDIUM, ThinkingLevel.HIGH}) {
            GeminiThinkingConfig config = GeminiProvider.mapThinking(level);
            assertThat(config)
                    .as("ThinkingLevel.%s must yield a non-null GeminiThinkingConfig", level)
                    .isNotNull();
        }
    }
}
