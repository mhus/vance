package de.mhus.vance.brain.ai.openai;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.CacheBoundary;
import de.mhus.vance.brain.ai.CacheTtl;
import de.mhus.vance.brain.ai.ThinkingLevel;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Cache-parameter mapping rules in {@link OpenAiProvider}. Targets the
 * static helpers directly — building the {@code OpenAiChatModel} pair
 * would need a live HTTP client.
 */
class OpenAiProviderCacheParametersTest {

    private static final AiChatConfig CONFIG =
            new AiChatConfig("openai", "gpt-4o-mini", "sk-test-key");

    @Test
    void cacheParameters_globalSwitchOff_returnsEmpty() {
        Map<String, Object> params = OpenAiProvider.buildCacheParameters(
                CONFIG, AiChatOptions.defaults(), false);

        assertThat(params).isEmpty();
    }

    @Test
    void cacheParameters_boundaryNone_returnsEmpty() {
        AiChatOptions options = AiChatOptions.builder()
                .cacheBoundary(CacheBoundary.NONE)
                .build();

        Map<String, Object> params = OpenAiProvider.buildCacheParameters(
                CONFIG, options, true);

        assertThat(params).isEmpty();
    }

    @Test
    void cacheParameters_default5min_emitsKeyOnly() {
        Map<String, Object> params = OpenAiProvider.buildCacheParameters(
                CONFIG, AiChatOptions.defaults(), true);

        assertThat(params)
                .containsOnlyKeys("prompt_cache_key");
        assertThat(params.get("prompt_cache_key"))
                .asString()
                .startsWith("vance-")
                .hasSize("vance-".length() + 16);
    }

    @Test
    void cacheParameters_long1h_emitsKeyAndRetention() {
        AiChatOptions options = AiChatOptions.builder()
                .cacheTtl(CacheTtl.LONG_1H)
                .build();

        Map<String, Object> params = OpenAiProvider.buildCacheParameters(
                CONFIG, options, true);

        assertThat(params)
                .containsEntry("prompt_cache_retention", "24h")
                .containsKey("prompt_cache_key");
    }

    @Test
    void deriveCacheKey_isDeterministicAcrossCalls() {
        String first = OpenAiProvider.deriveCacheKey(CONFIG);
        String second = OpenAiProvider.deriveCacheKey(CONFIG);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void deriveCacheKey_differsByModel() {
        String key1 = OpenAiProvider.deriveCacheKey(
                new AiChatConfig("openai", "gpt-4o-mini", "sk-key"));
        String key2 = OpenAiProvider.deriveCacheKey(
                new AiChatConfig("openai", "gpt-4o", "sk-key"));

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void deriveCacheKey_differsByApiKey() {
        String tenantA = OpenAiProvider.deriveCacheKey(
                new AiChatConfig("openai", "gpt-4o-mini", "sk-tenant-a"));
        String tenantB = OpenAiProvider.deriveCacheKey(
                new AiChatConfig("openai", "gpt-4o-mini", "sk-tenant-b"));

        assertThat(tenantA).isNotEqualTo(tenantB);
    }

    @Test
    void deriveCacheKey_doesNotLeakApiKey() {
        String key = OpenAiProvider.deriveCacheKey(
                new AiChatConfig("openai", "gpt-4o-mini", "sk-secret-leak"));

        assertThat(key).doesNotContain("sk-secret-leak");
    }

    @Test
    void mapReasoningEffort_offReturnsNull() {
        assertThat(OpenAiProvider.mapReasoningEffort(ThinkingLevel.OFF)).isNull();
    }

    @Test
    void mapReasoningEffort_emitsLowerCaseWireName() {
        assertThat(OpenAiProvider.mapReasoningEffort(ThinkingLevel.MINIMAL)).isEqualTo("minimal");
        assertThat(OpenAiProvider.mapReasoningEffort(ThinkingLevel.LOW)).isEqualTo("low");
        assertThat(OpenAiProvider.mapReasoningEffort(ThinkingLevel.MEDIUM)).isEqualTo("medium");
        assertThat(OpenAiProvider.mapReasoningEffort(ThinkingLevel.HIGH)).isEqualTo("high");
    }
}
