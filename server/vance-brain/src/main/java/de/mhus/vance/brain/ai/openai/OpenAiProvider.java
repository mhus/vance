package de.mhus.vance.brain.ai.openai;

import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelProvider;
import de.mhus.vance.brain.ai.CacheBoundary;
import de.mhus.vance.brain.ai.CacheTtl;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.StandardAiChat;
import dev.langchain4j.model.openai.OpenAiChatModel;
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
 * OpenAI backend for {@link AiModelProvider}.
 *
 * <p>Builds {@link OpenAiChatModel} (sync) and
 * {@link OpenAiStreamingChatModel} (streaming) with identical parameters,
 * wraps them in a shared {@link StandardAiChat}.
 *
 * <p>The base URL is configurable via {@code vance.ai.openai.base-url} so
 * the same provider can target OpenAI proper or any OpenAI-compatible
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
 * symmetric kill-switch with the Anthropic provider, even though
 * OpenAI's server-side prefix cache can't truly be disabled from the
 * client side (it just stops being optimised).
 */
@Component
@Slf4j
public class OpenAiProvider implements AiModelProvider {

    public static final String NAME = ProviderType.OPENAI.wireName();

    private final String baseUrl;
    private final boolean cacheEnabled;

    public OpenAiProvider(
            @Value("${vance.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${vance.ai.cache.enabled:true}") boolean cacheEnabled) {
        this.baseUrl = baseUrl;
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
    public AiChat createChat(AiChatConfig config, AiChatOptions options) {
        if (!NAME.equals(config.provider())) {
            throw new AiChatException(
                    "OpenAiProvider received config for provider '" + config.provider() + "'");
        }
        Duration timeout = Duration.ofSeconds(options.getTimeoutSeconds());
        Map<String, Object> cacheParams = buildCacheParameters(config, options, cacheEnabled);
        try {
            OpenAiChatModel.OpenAiChatModelBuilder syncBuilder = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(config.apiKey())
                    .modelName(config.modelName())
                    .temperature(options.getTemperature())
                    .maxTokens(options.getMaxTokens())
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
                            .timeout(timeout)
                            .logRequests(options.getLogRequests())
                            .logResponses(options.getLogRequests());
            if (!cacheParams.isEmpty()) {
                syncBuilder.customParameters(cacheParams);
                streamBuilder.customParameters(cacheParams);
            }
            OpenAiChatModel sync = syncBuilder.build();
            OpenAiStreamingChatModel streaming = streamBuilder.build();
            log.debug("Built OpenAI chat pair: model='{}', baseUrl='{}', maxTokens={}, "
                            + "temperature={}, cacheParams={}",
                    config.modelName(), baseUrl, options.getMaxTokens(),
                    options.getTemperature(), cacheParams.keySet());
            return new StandardAiChat(
                    config.fullName(), ProviderType.OPENAI, sync, streaming, options);
        } catch (RuntimeException e) {
            throw new AiChatException(
                    "Failed to build OpenAI chat for " + config.fullName(), e);
        }
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
