package de.mhus.vance.brain.ai.openai;

import de.mhus.vance.brain.ai.AbstractChatProvider;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.CacheBoundary;
import de.mhus.vance.brain.ai.CacheTtl;
import de.mhus.vance.brain.ai.LlmResponseSanitizer;
import de.mhus.vance.brain.ai.ModelCapability;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.ThinkingLevel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OpenAI backend.
 *
 * <p>Builds {@link OpenAiChatModel} (sync) + {@link OpenAiStreamingChatModel}
 * (streaming). The base URL is configurable via {@code vance.ai.openai.base-url}
 * so the same provider can target OpenAI proper or any OpenAI-compatible
 * gateway. Note: LM Studio has its own provider; this one is reserved
 * for OpenAI-proper and explicit OpenAI-protocol bridges (not a generic
 * "anything-OpenAI-shaped" catch-all).
 *
 * <p><b>Prompt caching.</b> OpenAI does prefix-hash caching server-side
 * for {@code gpt-4o}/{@code gpt-4o-mini} and newer (no client marker
 * required, unlike Anthropic). This provider opts into the two
 * client-side levers that materially improve hit rate:
 * <ul>
 *   <li>{@code prompt_cache_key} — a stable string derived from
 *       {@code apiKey + modelName} so all requests for one
 *       tenant-and-model land on the same backend pod (sticky
 *       routing). Mitigates cache-miss spikes once a tenant exceeds
 *       ~15 req/min.</li>
 *   <li>{@code prompt_cache_retention} — mapped from
 *       {@link AiChatOptions#getCacheTtl()}: {@link CacheTtl#LONG_1H}
 *       requests {@code "24h"} (extended caching, paid GPU-local KV
 *       storage); {@link CacheTtl#DEFAULT_5MIN} leaves the field
 *       unset so OpenAI uses its 5–10 min in-memory default.</li>
 * </ul>
 * Both fields are dropped when {@link AiChatOptions#getCacheBoundary()}
 * is {@link CacheBoundary#NONE} or the global
 * {@code vance.ai.cache.enabled} switch is {@code false} — that's the
 * symmetric kill-switch with the Anthropic provider.
 *
 * <p>Cross-cutting orchestration lives in {@link AbstractChatProvider}.
 */
@Component
@Slf4j
public class OpenAiProvider extends AbstractChatProvider {

    private final String defaultBaseUrl;
    private final boolean cacheEnabled;

    public OpenAiProvider(
            ModelCatalog modelCatalog,
            LlmResponseSanitizer responseSanitizer,
            @Value("${vance.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${vance.ai.cache.enabled:true}") boolean cacheEnabled) {
        super(modelCatalog, responseSanitizer);
        this.defaultBaseUrl = baseUrl;
        this.cacheEnabled = cacheEnabled;
        if (!cacheEnabled) {
            log.info("OpenAI prompt-cache hints DISABLED via vance.ai.cache.enabled=false "
                    + "— server-side prefix caching still applies but won't be optimised");
        }
    }

    @Override
    public ProviderType getType() {
        return ProviderType.OPENAI;
    }

    @Override
    protected BuiltChat buildModels(
            AiChatConfig config, AiChatOptions options, ModelInfo modelInfo) {
        Duration timeout = Duration.ofSeconds(
                modelInfo.effectiveTimeoutSeconds(options.getTimeoutSeconds()));
        Map<String, Object> cacheParams = buildCacheParameters(config, options, cacheEnabled);
        Integer seed = options.getSeed() == null ? null : options.getSeed().intValue();
        // Per-tenant override (cortecs / OpenRouter / vLLM) wins over the Spring
        // boot-time default. Empty / unset falls back to vance.ai.openai.base-url.
        String baseUrl = config.baseUrl() != null ? config.baseUrl() : defaultBaseUrl;
        OpenAiChatModel.OpenAiChatModelBuilder syncBuilder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .temperature(options.getTemperature())
                .maxTokens(options.getMaxTokens())
                .topP(options.getTopP())
                .frequencyPenalty(options.getFrequencyPenalty())
                .presencePenalty(options.getPresencePenalty())
                .seed(seed)
                .stop(options.getStopSequences())
                .timeout(timeout)
                .logRequests(options.getLogRequests())
                .logResponses(options.getLogRequests());
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder streamBuilder =
                OpenAiStreamingChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(config.apiKey())
                        .modelName(config.modelName())
                        .temperature(options.getTemperature())
                        .maxTokens(options.getMaxTokens())
                        .topP(options.getTopP())
                        .frequencyPenalty(options.getFrequencyPenalty())
                        .presencePenalty(options.getPresencePenalty())
                        .seed(seed)
                        .stop(options.getStopSequences())
                        .timeout(timeout)
                        .logRequests(options.getLogRequests())
                        .logResponses(options.getLogRequests());
        if (!cacheParams.isEmpty()) {
            syncBuilder.customParameters(cacheParams);
            streamBuilder.customParameters(cacheParams);
        }
        ThinkingLevel effectiveLevel = gateThinkingLevel(
                options.getThinkingLevel(), modelInfo);
        String reasoningEffort = mapReasoningEffort(effectiveLevel);
        if (reasoningEffort != null) {
            OpenAiChatRequestParameters defaults = OpenAiChatRequestParameters.builder()
                    .reasoningEffort(reasoningEffort)
                    .build();
            syncBuilder.defaultRequestParameters(defaults);
            streamBuilder.defaultRequestParameters(defaults);
        }
        log.debug("Built OpenAI chat pair: model='{}', baseUrl='{}', maxTokens={}, "
                        + "temperature={}, cacheParams={}, reasoningEffort={}",
                config.modelName(), baseUrl, options.getMaxTokens(),
                options.getTemperature(), cacheParams.keySet(), reasoningEffort);
        return new BuiltChat(syncBuilder.build(), streamBuilder.build());
    }

    /**
     * Build the {@code prompt_cache_key} + {@code prompt_cache_retention}
     * pair. Empty when caching is disabled by the global switch or by the
     * per-call boundary. Package-private + static so unit tests can
     * pin the mapping without standing the bean up.
     */
    static Map<String, Object> buildCacheParameters(
            AiChatConfig config, AiChatOptions options, boolean cacheEnabled) {
        if (!cacheEnabled || options.getCacheBoundary() == CacheBoundary.NONE) {
            return Map.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("prompt_cache_key", deriveCacheKey(config));
        if (options.getCacheTtl() == CacheTtl.LONG_1H) {
            params.put("prompt_cache_retention", "24h");
        }
        return params;
    }

    /**
     * Drop the requested thinking level to {@link ThinkingLevel#OFF}
     * when the resolved model does not advertise
     * {@link ModelCapability#THINKING} in {@code ai-models.yaml}. The
     * non-reasoning OpenAI / open-weight gateway models reject
     * {@code reasoning_effort} with an API error; the gate keeps recipe
     * authors out of "which gpt-* model is a reasoning model" trivia.
     */
    public static ThinkingLevel gateThinkingLevel(
            @org.jspecify.annotations.Nullable ThinkingLevel requested, ModelInfo modelInfo) {
        if (requested == null || requested == ThinkingLevel.OFF) {
            return ThinkingLevel.OFF;
        }
        if (modelInfo.supports(ModelCapability.THINKING)) {
            return requested;
        }
        log.debug("OpenAI model '{}/{}' lacks THINKING capability — "
                        + "downgrading requested level {} → OFF for this call",
                modelInfo.provider(), modelInfo.modelName(), requested);
        return ThinkingLevel.OFF;
    }

    /**
     * Map a {@link ThinkingLevel} to OpenAI's {@code reasoning_effort}
     * wire value, or {@code null} when no field should be set on the
     * request (the default for {@link ThinkingLevel#OFF}). Reasoning
     * models like o1/o3/gpt-5 honor "minimal", "low", "medium", "high";
     * non-reasoning models reject the field with an API error — that's
     * a recipe/model mismatch and should fail loudly.
     */
    public static @org.jspecify.annotations.Nullable String mapReasoningEffort(ThinkingLevel level) {
        if (level == null || level == ThinkingLevel.OFF) {
            return null;
        }
        return level.wireName();
    }

    /**
     * Derive a stable per-tenant-and-model cache key. Hashing rather
     * than passing the API key directly so the value can appear in
     * logs / traces without leaking the credential.
     */
    static String deriveCacheKey(AiChatConfig config) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(config.modelName().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(config.apiKey().getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            return "vance-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
