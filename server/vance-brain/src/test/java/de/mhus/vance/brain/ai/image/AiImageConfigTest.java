package de.mhus.vance.brain.ai.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.ai.ProviderType;
import org.junit.jupiter.api.Test;

class AiImageConfigTest {

    @Test
    void valid_minimal_record_constructs() {
        AiImageConfig cfg = new AiImageConfig(
                "openai", "openai", "gpt-image-1",
                "sk-secret", null, "1:1", 360);

        assertThat(cfg.provider()).isEqualTo("openai");
        assertThat(cfg.modelName()).isEqualTo("gpt-image-1");
        assertThat(cfg.aspectRatio()).isEqualTo("1:1");
        assertThat(cfg.timeoutSeconds()).isEqualTo(360);
        assertThat(cfg.baseUrl()).isNull();
    }

    @Test
    void blank_provider_rejected() {
        assertThatThrownBy(() -> new AiImageConfig(
                " ", "openai", "gpt-image-1", "key", null, "1:1", 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void blank_providerInstance_rejected() {
        assertThatThrownBy(() -> new AiImageConfig(
                "openai", "", "gpt-image-1", "key", null, "1:1", 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerInstance");
    }

    @Test
    void blank_modelName_rejected() {
        assertThatThrownBy(() -> new AiImageConfig(
                "openai", "openai", " ", "key", null, "1:1", 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelName");
    }

    @Test
    void blank_apiKey_rejected() {
        assertThatThrownBy(() -> new AiImageConfig(
                "openai", "openai", "gpt-image-1", "", null, "1:1", 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void blank_aspectRatio_rejected() {
        assertThatThrownBy(() -> new AiImageConfig(
                "openai", "openai", "gpt-image-1", "key", null, " ", 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aspectRatio");
    }

    @Test
    void non_positive_timeout_rejected() {
        assertThatThrownBy(() -> new AiImageConfig(
                "openai", "openai", "gpt-image-1", "key", null, "1:1", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutSeconds");
        assertThatThrownBy(() -> new AiImageConfig(
                "openai", "openai", "gpt-image-1", "key", null, "1:1", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutSeconds");
    }

    @Test
    void blank_baseUrl_normalized_to_null() {
        AiImageConfig cfg = new AiImageConfig(
                "openai", "openai", "gpt-image-1", "key", "  ", "1:1", 60);

        assertThat(cfg.baseUrl()).isNull();
    }

    @Test
    void fullName_uses_provider_instance() {
        AiImageConfig cfg = new AiImageConfig(
                "openai", "my-route", "gpt-image-1", "key", null, "1:1", 60);

        assertThat(cfg.fullName()).isEqualTo("my-route:gpt-image-1");
    }

    @Test
    void providerType_resolves_wire_name() {
        AiImageConfig cfg = new AiImageConfig(
                "openai", "openai", "gpt-image-1", "key", null, "1:1", 60);

        assertThat(cfg.providerType()).isEqualTo(ProviderType.OPENAI);
    }
}
