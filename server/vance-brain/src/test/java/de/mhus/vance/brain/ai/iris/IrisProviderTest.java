package de.mhus.vance.brain.ai.iris;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.ProviderType;
import org.junit.jupiter.api.Test;

class IrisProviderTest {

    @Test
    void irisIsItsOwnProviderTypeThatNeedsAKey() {
        assertThat(ProviderType.fromWireName("iris")).contains(ProviderType.IRIS);
        assertThat(ProviderType.IRIS.requiresApiKey()).isTrue();
        assertThat(ProviderType.IRIS.wireName()).isEqualTo("iris");
    }

    @Test
    void thereIsNoFallbackToTheOpenAiEndpoint() {
        assertThatThrownBy(() -> IrisProvider.requireBaseUrl(null, "iris"))
                .isInstanceOf(AiChatException.class)
                .hasMessageContaining("ai.provider.iris.baseUrl");
        assertThatThrownBy(() -> IrisProvider.requireBaseUrl("  ", "iris")).isInstanceOf(AiChatException.class);
        IrisProvider.requireBaseUrl("https://iris.example/v1", "iris");
    }
}
