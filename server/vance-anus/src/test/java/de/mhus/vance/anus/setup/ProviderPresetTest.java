package de.mhus.vance.anus.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ProviderPresetTest {

    @Test
    void custom_isOpenAiCompatibleGatewayRequiringBaseUrl() {
        ProviderPreset custom = ProviderPreset.CUSTOM;

        assertThat(custom.requiresBaseUrl()).isTrue();
        assertThat(custom.settingsId()).isEqualTo("openai");
        assertThat(custom.defaultModel()).isEmpty(); // no sensible default — operator sets it
        assertThat(custom.supportsEmbedding()).isFalse(); // custom chat endpoint may lack embeddings
    }

    @Test
    void onlyCustom_requiresBaseUrl() {
        assertThat(Arrays.stream(ProviderPreset.values())
                        .filter(ProviderPreset::requiresBaseUrl))
                .containsExactly(ProviderPreset.CUSTOM);
    }

    @Test
    void fromSettingsId_openai_prefersOpenAiOverCustom() {
        // Both OPENAI and CUSTOM use the "openai" settings id; read-back must
        // resolve to the first match so pre-filling stays deterministic.
        assertThat(ProviderPreset.fromSettingsId("openai")).isEqualTo(ProviderPreset.OPENAI);
    }

    @Test
    void fromSettingsId_unknown_returnsNull() {
        assertThat(ProviderPreset.fromSettingsId("does-not-exist")).isNull();
    }
}
