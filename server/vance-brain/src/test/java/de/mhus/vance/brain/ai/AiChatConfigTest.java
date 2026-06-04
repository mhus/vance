package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the back-compat invariants of {@link AiChatConfig} after the
 * named-instance refactor: the three- and four-argument constructors
 * still work and default {@code providerInstance} to the protocol
 * wire-name. {@link AiChatConfig#fullName()} surfaces the instance in
 * the log-friendly representation so named instances show up clearly.
 */
class AiChatConfigTest {

    @Test
    void threeArg_defaultsInstanceToProvider() {
        AiChatConfig cfg = new AiChatConfig("openai", "gpt-4o-mini", "sk-test");

        assertThat(cfg.provider()).isEqualTo("openai");
        assertThat(cfg.providerInstance()).isEqualTo("openai");
        assertThat(cfg.modelName()).isEqualTo("gpt-4o-mini");
        assertThat(cfg.baseUrl()).isNull();
        assertThat(cfg.fullName()).isEqualTo("openai:gpt-4o-mini");
    }

    @Test
    void fourArg_defaultsInstanceToProvider() {
        AiChatConfig cfg = new AiChatConfig(
                "openai", "gpt-4o-mini", "sk-test", "https://gw.example.com/v1");

        assertThat(cfg.providerInstance()).isEqualTo("openai");
        assertThat(cfg.baseUrl()).isEqualTo("https://gw.example.com/v1");
    }

    @Test
    void fiveArg_canonicalNamedInstance() {
        AiChatConfig cfg = new AiChatConfig(
                "openai", "deepseek-direct", "deepseek-v4-flash",
                "sk-deepseek", "https://api.deepseek.com/v1");

        assertThat(cfg.provider()).isEqualTo("openai");
        assertThat(cfg.providerInstance()).isEqualTo("deepseek-direct");
        assertThat(cfg.modelName()).isEqualTo("deepseek-v4-flash");
        // fullName uses the instance — that's what the user actually wrote.
        assertThat(cfg.fullName()).isEqualTo("deepseek-direct:deepseek-v4-flash");
    }

    @Test
    void blankInstance_rejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new AiChatConfig("openai", "  ", "gpt-4o-mini", "sk", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerInstance");
    }
}
