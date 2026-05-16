package de.mhus.vance.brain.ai.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.ModelCapability;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.ai.ThinkingLevel;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GeminiProviderThinkingTest {

    private static ModelInfo modelWith(ModelCapability... caps) {
        Set<ModelCapability> set = caps.length == 0
                ? Set.of()
                : EnumSet.copyOf(java.util.Arrays.asList(caps));
        return new ModelInfo("gemini", "test-model", 1_000_000, 8192, ModelSize.LARGE, set,
                ModelInfo.DEFAULT_TIMEOUT_SECONDS);
    }

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

    @Test
    void gateThinkingLevel_modelWithoutCapability_downgradesToOff() {
        ModelInfo noThinking = modelWith(ModelCapability.VISION, ModelCapability.PDF);
        assertThat(GeminiProvider.gateThinkingLevel(ThinkingLevel.HIGH, noThinking))
                .isEqualTo(ThinkingLevel.OFF);
    }

    @Test
    void gateThinkingLevel_modelWithCapability_passesThrough() {
        ModelInfo thinkingOk = modelWith(ModelCapability.THINKING);
        assertThat(GeminiProvider.gateThinkingLevel(ThinkingLevel.HIGH, thinkingOk))
                .isEqualTo(ThinkingLevel.HIGH);
    }

    @Test
    void gateThinkingLevel_offRequest_isOffRegardlessOfCapability() {
        assertThat(GeminiProvider.gateThinkingLevel(ThinkingLevel.OFF, modelWith()))
                .isEqualTo(ThinkingLevel.OFF);
        assertThat(GeminiProvider.gateThinkingLevel(null, modelWith(ModelCapability.THINKING)))
                .isEqualTo(ThinkingLevel.OFF);
    }
}
