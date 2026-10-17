package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.chatmodel.ChatModelDto;
import de.mhus.vance.api.chatmodel.ChatModelPricingDto;
import de.mhus.vance.api.chatmodel.ChatModelStateDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatModelSupportTest {

    private static ChatModelDto model(String spec, boolean thinking) {
        String[] parts = spec.split(":", 2);
        return ChatModelDto.builder()
                .spec(spec)
                .providerInstance(parts[0])
                .modelName(parts[1])
                .size("LARGE")
                .thinking(thinking)
                .contextWindow(128000)
                .build();
    }

    private final List<ChatModelDto> models = List.of(
            model("anthropic:claude-sonnet", true), model("cortecs:deepseek-v4", false), model("cortecs:glm-5", true));

    private static ChatModelStateDto state(String model, boolean overridden, String thinking) {
        return ChatModelStateDto.builder()
                .model(model)
                .modelOverridden(overridden)
                .recipeModel("anthropic:claude-sonnet")
                .thinking(thinking)
                .modelSupportsThinking(true)
                .build();
    }

    @Test
    void filter_matchesEveryTermCaseInsensitively() {
        assertThat(ChatModelSupport.filter(models, "Cortecs GLM"))
                .extracting(ChatModelDto::getSpec)
                .containsExactly("cortecs:glm-5");
        assertThat(ChatModelSupport.filter(models, " ")).hasSize(3);
    }

    @Test
    void formatPrice_inputOutputPerMillion() {
        assertThat(ChatModelSupport.formatPrice(ChatModelPricingDto.builder()
                        .currency("USD")
                        .inputPerMTok(3)
                        .outputPerMTok(15)
                        .build()))
                .isEqualTo("3.00 / 15.00 USD");
        assertThat(ChatModelSupport.formatPrice(ChatModelPricingDto.builder()
                        .currency("EUR")
                        .inputPerMTok(0.05)
                        .outputPerMTok(0.4)
                        .build()))
                .isEqualTo("0.050 / 0.40 EUR");
        assertThat(ChatModelSupport.formatPrice(null)).isEqualTo("no price");
    }

    @Test
    void formatContext_shortens() {
        assertThat(ChatModelSupport.formatContext(128000)).isEqualTo("128K");
        assertThat(ChatModelSupport.formatContext(1_000_000)).isEqualTo("1M");
        assertThat(ChatModelSupport.formatContext(1_500_000)).isEqualTo("1.5M");
    }

    @Test
    void formatRow_marksCurrentAndReasoning() {
        assertThat(ChatModelSupport.formatRow(models.get(2), true))
                .startsWith("* cortecs:glm-5")
                .contains("reasoning")
                .contains("no price");
        assertThat(ChatModelSupport.formatRow(models.get(1), false))
                .startsWith("  cortecs:deepseek-v4")
                .doesNotContain("reasoning");
    }

    @Test
    void supportsThinking_followsTheSelectedModel() {
        ChatModelStateDto s = state("anthropic:claude-sonnet", false, "off");
        assertThat(ChatModelSupport.supportsThinking("cortecs:deepseek-v4", s, models))
                .isFalse();
        assertThat(ChatModelSupport.supportsThinking(ChatModelSupport.RECIPE_DEFAULT, s, models))
                .isTrue();
    }

    @Test
    void stepsFor_sendsOnlyChanges_modelFirst() {
        ChatModelStateDto plain = state("anthropic:claude-sonnet", false, "off");
        assertThat(ChatModelSupport.stepsFor(plain, ChatModelSupport.RECIPE_DEFAULT, "off"))
                .isEmpty();
        assertThat(ChatModelSupport.stepsFor(plain, "cortecs:glm-5", "high"))
                .containsExactly(
                        new ChatModelSupport.Step("model", "cortecs:glm-5"),
                        new ChatModelSupport.Step("thinking", "high"));

        ChatModelStateDto overridden = state("cortecs:glm-5", true, "off");
        assertThat(ChatModelSupport.initialSelection(overridden)).isEqualTo("cortecs:glm-5");
        assertThat(ChatModelSupport.stepsFor(overridden, ChatModelSupport.RECIPE_DEFAULT, "off"))
                .containsExactly(new ChatModelSupport.Step("model", "clear"));
    }
}
