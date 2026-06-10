package de.mhus.vance.brain.ai.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImageModelInfoTest {

    @Test
    void empty_aspect_ratios_default_to_one_to_one() {
        ImageModelInfo info = new ImageModelInfo(
                "openai", "gpt-image-1", Set.of(), 4000,
                Map.of("standard", 0.04), 360);

        assertThat(info.supportedAspectRatios()).containsExactly("1:1");
    }

    @Test
    void null_aspect_ratios_default_to_one_to_one() {
        ImageModelInfo info = new ImageModelInfo(
                "openai", "gpt-image-1", null, 4000,
                Map.of("standard", 0.04), 360);

        assertThat(info.supportedAspectRatios()).containsExactly("1:1");
    }

    @Test
    void null_cost_map_normalizes_to_empty() {
        ImageModelInfo info = new ImageModelInfo(
                "openai", "gpt-image-1", Set.of("1:1"), 4000, null, 360);

        assertThat(info.costPerImage()).isEmpty();
        assertThat(info.costFor("standard")).isNull();
    }

    @Test
    void non_positive_max_prompt_chars_falls_back_to_default() {
        ImageModelInfo zero = new ImageModelInfo(
                "openai", "gpt-image-1", Set.of("1:1"), 0,
                Map.of(), 360);
        ImageModelInfo negative = new ImageModelInfo(
                "openai", "gpt-image-1", Set.of("1:1"), -10,
                Map.of(), 360);

        assertThat(zero.maxPromptChars()).isEqualTo(ImageModelInfo.DEFAULT_MAX_PROMPT_CHARS);
        assertThat(negative.maxPromptChars()).isEqualTo(ImageModelInfo.DEFAULT_MAX_PROMPT_CHARS);
    }

    @Test
    void non_positive_timeout_falls_back_to_default() {
        ImageModelInfo info = new ImageModelInfo(
                "openai", "gpt-image-1", Set.of("1:1"), 4000,
                Map.of(), 0);

        assertThat(info.timeoutSeconds()).isEqualTo(ImageModelInfo.DEFAULT_TIMEOUT_SECONDS);
    }

    @Test
    void supportsAspectRatio_returns_true_for_listed_ratio() {
        ImageModelInfo info = new ImageModelInfo(
                "openai", "gpt-image-1", Set.of("1:1", "16:9"), 4000,
                Map.of(), 360);

        assertThat(info.supportsAspectRatio("1:1")).isTrue();
        assertThat(info.supportsAspectRatio("16:9")).isTrue();
        assertThat(info.supportsAspectRatio("3:4")).isFalse();
    }

    @Test
    void costFor_returns_value_for_known_tier() {
        ImageModelInfo info = new ImageModelInfo(
                "openai", "gpt-image-1", Set.of("1:1"), 4000,
                Map.of("standard", 0.04, "hd", 0.08), 360);

        assertThat(info.costFor("standard")).isEqualTo(0.04);
        assertThat(info.costFor("hd")).isEqualTo(0.08);
        assertThat(info.costFor("ultra")).isNull();
    }

    @Test
    void cost_map_is_defensively_copied() {
        Map<String, Double> mutable = new java.util.HashMap<>();
        mutable.put("standard", 0.04);
        ImageModelInfo info = new ImageModelInfo(
                "openai", "gpt-image-1", Set.of("1:1"), 4000, mutable, 360);

        mutable.put("hd", 0.99);

        // The record's view should not see the mutation.
        assertThat(info.costFor("hd")).isNull();
    }
}
