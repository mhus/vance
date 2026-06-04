package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.SettingService;
import org.junit.jupiter.api.Test;

/**
 * Tests the per-instance settings paths used by
 * {@link ChatBehaviorBuilder#resolveOne}: api-key and base-URL lookups read
 * {@code ai.provider.<instance>.apiKey} / {@code .baseUrl}, where
 * {@code instance} comes from the {@link AiModelResolver.Resolved} and equals
 * the protocol wire-name for direct specs.
 */
class ChatBehaviorBuilderTest {

    @Test
    void resolveOne_namedInstance_readsApiKeyFromInstancePath() {
        AiModelResolver resolver = mock(AiModelResolver.class);
        SettingService settings = mock(SettingService.class);

        when(resolver.resolveOrDefault(any(), any(), any(), any()))
                .thenReturn(new AiModelResolver.Resolved(
                        "openai", "deepseek-direct", "deepseek-v4-flash"));
        when(settings.getDecryptedPasswordCascade(
                eq("acme"), any(), any(), eq("ai.provider.deepseek-direct.apiKey")))
                .thenReturn("sk-deepseek-key");
        when(settings.getStringValueCascade(
                eq("acme"), any(), any(), eq("ai.provider.deepseek-direct.baseUrl")))
                .thenReturn("https://api.deepseek.com/v1");

        AiChatConfig cfg = ChatBehaviorBuilder.resolveOne(
                "deepseek-direct:deepseek-v4-flash",
                "acme", null, null, settings, resolver);

        assertThat(cfg.provider()).isEqualTo("openai");
        assertThat(cfg.providerInstance()).isEqualTo("deepseek-direct");
        assertThat(cfg.modelName()).isEqualTo("deepseek-v4-flash");
        assertThat(cfg.apiKey()).isEqualTo("sk-deepseek-key");
        assertThat(cfg.baseUrl()).isEqualTo("https://api.deepseek.com/v1");

        // Never reads the protocol-keyed path when a named instance is used.
        verify(settings, never()).getDecryptedPasswordCascade(
                any(), any(), any(), eq("ai.provider.openai.apiKey"));
    }

    @Test
    void resolveOne_defaultInstance_readsApiKeyFromProtocolPath() {
        // Direct openai:gpt-4o-mini — instance == provider, so the api-key
        // path stays at ai.provider.openai.apiKey (backward-compat).
        AiModelResolver resolver = mock(AiModelResolver.class);
        SettingService settings = mock(SettingService.class);

        when(resolver.resolveOrDefault(any(), any(), any(), any()))
                .thenReturn(AiModelResolver.Resolved.direct("openai", "gpt-4o-mini"));
        when(settings.getDecryptedPasswordCascade(
                eq("acme"), any(), any(), eq("ai.provider.openai.apiKey")))
                .thenReturn("sk-real-openai");

        AiChatConfig cfg = ChatBehaviorBuilder.resolveOne(
                "openai:gpt-4o-mini",
                "acme", null, null, settings, resolver);

        assertThat(cfg.providerInstance()).isEqualTo("openai");
        assertThat(cfg.apiKey()).isEqualTo("sk-real-openai");
    }

    @Test
    void resolveApiKey_missingKeyForRequiredProvider_throws() {
        SettingService settings = mock(SettingService.class);
        when(settings.getDecryptedPasswordCascade(
                any(), any(), any(), eq("ai.provider.deepseek-direct.apiKey")))
                .thenReturn(null);

        assertThatThrownBy(() -> ChatBehaviorBuilder.resolveApiKey(
                "openai", "deepseek-direct",
                "acme", null, null, settings))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deepseek-direct")
                .hasMessageContaining("ai.provider.deepseek-direct.apiKey");
    }

    @Test
    void resolveApiKey_keylessProvider_returnsPlaceholder() {
        SettingService settings = mock(SettingService.class);

        String key = ChatBehaviorBuilder.resolveApiKey(
                "ollama", "my-local-llama",
                "acme", null, null, settings);

        assertThat(key).isEqualTo(ChatBehaviorBuilder.KEYLESS_PLACEHOLDER);
    }
}
