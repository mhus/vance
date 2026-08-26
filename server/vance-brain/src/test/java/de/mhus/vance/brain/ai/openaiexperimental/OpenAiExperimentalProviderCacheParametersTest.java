package de.mhus.vance.brain.ai.openaiexperimental;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.CacheBoundary;
import de.mhus.vance.brain.ai.CacheTtl;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Cache-parameter mapping rules in {@link OpenAiExperimentalProvider}.
 *
 * <p>Targets the static helpers directly — building the
 * {@code OpenAiResponsesChatModel} pair would need a live HTTP client.
 * Mirrors {@code OpenAiProviderCacheParametersTest} one-for-one, except
 * that the cache map keys are {@code camelCase} here ({@code promptCacheKey}
 * / {@code promptCacheRetention}) because the Responses-API client
 * exposes them as typed builder methods, not as custom request parameters
 * the way chat-completions does. The underlying values and gating rules
 * are identical — a tenant switching an instance from {@code openai} to
 * {@code openai-experimental} keeps its cache locality (same key derivation).
 */
class OpenAiExperimentalProviderCacheParametersTest {

    private static final AiChatConfig CONFIG =
            new AiChatConfig("openai-experimental", "gpt-5.6-sol", "sk-test-key");

    @Test
    void cacheParameters_globalSwitchOff_returnsEmpty() {
        Map<String, Object> params = OpenAiExperimentalProvider.buildCacheParameters(
                CONFIG, AiChatOptions.defaults(), false);

        assertThat(params).isEmpty();
    }

    @Test
    void cacheParameters_boundaryNone_returnsEmpty() {
        AiChatOptions options = AiChatOptions.builder()
                .cacheBoundary(CacheBoundary.NONE)
                .build();

        Map<String, Object> params = OpenAiExperimentalProvider.buildCacheParameters(
                CONFIG, options, true);

        assertThat(params).isEmpty();
    }

    @Test
    void cacheParameters_default5min_emitsKeyOnly() {
        Map<String, Object> params = OpenAiExperimentalProvider.buildCacheParameters(
                CONFIG, AiChatOptions.defaults(), true);

        assertThat(params)
                .containsOnlyKeys("promptCacheKey");
        assertThat(params.get("promptCacheKey"))
                .asString()
                .startsWith("vance-")
                .hasSize("vance-".length() + 16);
    }

    @Test
    void cacheParameters_long1h_emitsKeyAndRetention() {
        AiChatOptions options = AiChatOptions.builder()
                .cacheTtl(CacheTtl.LONG_1H)
                .build();

        Map<String, Object> params = OpenAiExperimentalProvider.buildCacheParameters(
                CONFIG, options, true);

        assertThat(params)
                .containsEntry("promptCacheRetention", "24h")
                .containsKey("promptCacheKey");
    }

    @Test
    void deriveCacheKey_isDeterministicAcrossCalls() {
        String first = OpenAiExperimentalProvider.deriveCacheKey(CONFIG);
        String second = OpenAiExperimentalProvider.deriveCacheKey(CONFIG);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void deriveCacheKey_differsByModel() {
        String key1 = OpenAiExperimentalProvider.deriveCacheKey(
                new AiChatConfig("openai-experimental", "gpt-5.6-sol", "sk-key"));
        String key2 = OpenAiExperimentalProvider.deriveCacheKey(
                new AiChatConfig("openai-experimental", "gpt-5.6-luna", "sk-key"));

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void deriveCacheKey_differsByApiKey() {
        String tenantA = OpenAiExperimentalProvider.deriveCacheKey(
                new AiChatConfig("openai-experimental", "gpt-5.6-sol", "sk-tenant-a"));
        String tenantB = OpenAiExperimentalProvider.deriveCacheKey(
                new AiChatConfig("openai-experimental", "gpt-5.6-sol", "sk-tenant-b"));

        assertThat(tenantA).isNotEqualTo(tenantB);
    }

    @Test
    void deriveCacheKey_doesNotLeakApiKey() {
        String key = OpenAiExperimentalProvider.deriveCacheKey(
                new AiChatConfig("openai-experimental", "gpt-5.6-sol", "sk-secret-leak"));

        assertThat(key).doesNotContain("sk-secret-leak");
    }

    @Test
    void deriveCacheKey_identicalToLegacyOpenAiProviderForSameInputs() {
        // A tenant switching an instance from `openai` to
        // `openai-experimental` must keep its cache locality — the key
        // is derived from (modelName, apiKey) only, provider-independent.
        // Cross-checking against the legacy provider's derivation pins
        // that contract.
        AiChatConfig legacyConfig =
                new AiChatConfig("openai", "gpt-5.6-sol", "sk-key");
        AiChatConfig experimentalConfig =
                new AiChatConfig("openai-experimental", "gpt-5.6-sol", "sk-key");

        assertThat(OpenAiExperimentalProvider.deriveCacheKey(experimentalConfig))
                .isEqualTo(de.mhus.vance.brain.ai.openai.OpenAiProvider.deriveCacheKey(legacyConfig));
    }
}
