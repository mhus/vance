package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The runtime {@code //model} override in {@code engineParamOverrides} versus the recipe's params. */
class ChatBehaviorBuilderModelOverrideTest {

    private static ThinkProcessDocument process(Map<String, Object> params, Map<String, Object> overrides) {
        ThinkProcessDocument p = ThinkProcessDocument.builder().id("p1").build();
        p.setEngineParams(new HashMap<>(params));
        p.setEngineParamOverrides(new HashMap<>(overrides));
        return p;
    }

    @Test
    void readModelSpec_withoutOverride_usesRecipeModel() {
        ThinkProcessDocument p = process(Map.of("model", "default:chat"), Map.of());

        assertThat(ChatBehaviorBuilder.readModelSpec(p)).isEqualTo("default:chat");
        assertThat(ChatBehaviorBuilder.hasModelOverride(p)).isFalse();
    }

    @Test
    void readModelSpec_overrideWinsOverRecipe() {
        ThinkProcessDocument p = process(Map.of("model", "default:chat"), Map.of("model", "cortecs:glm-5"));

        assertThat(ChatBehaviorBuilder.readModelSpec(p)).isEqualTo("cortecs:glm-5");
        assertThat(ChatBehaviorBuilder.readRecipeModelSpec(p)).isEqualTo("default:chat");
        assertThat(ChatBehaviorBuilder.hasModelOverride(p)).isTrue();
    }

    @Test
    void readModelSpec_overrideIgnoresLegacyRecipeProvider() {
        ThinkProcessDocument p =
                process(Map.of("provider", "anthropic", "model", "claude-x"), Map.of("model", "cortecs:glm-5"));

        assertThat(ChatBehaviorBuilder.readModelSpec(p)).isEqualTo("cortecs:glm-5");
    }

    @Test
    void readFallbackAliases_withoutOverride_returnsRecipeChain() {
        ThinkProcessDocument p =
                process(Map.of("fallbackModels", List.of("default:fast", "default:analyze")), Map.of());

        assertThat(ChatBehaviorBuilder.readFallbackAliases(p)).containsExactly("default:fast", "default:analyze");
    }

    @Test
    void readFallbackAliases_whileOverrideSet_isEmpty() {
        ThinkProcessDocument p =
                process(Map.of("fallbackModels", List.of("default:fast")), Map.of("model", "cortecs:glm-5"));

        assertThat(ChatBehaviorBuilder.readFallbackAliases(p)).isEmpty();
    }
}
