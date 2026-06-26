package de.mhus.vance.brain.ai.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import de.mhus.vance.brain.ai.AbstractChatProvider;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.CacheBoundary;
import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import de.mhus.vance.brain.ai.LlmResponseSanitizer;
import de.mhus.vance.brain.ai.ModelCapability;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.parser.MessageParserRegistry;
import de.mhus.vance.brain.ai.ProviderListingHttp;
import de.mhus.vance.brain.ai.ProviderListingRequest;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.ThinkingLevel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Anthropic backend, built on top of {@link AnthropicDirectChatModel}
 * so prompt caching and beta headers are reachable. Replaces the
 * langchain4j-anthropic implementation (the latter doesn't expose
 * {@code cache_control}).
 *
 * <p>Per-call sync + streaming pair share the same {@link AnthropicClient}
 * — the SDK is thread-safe and reusing the client keeps the connection
 * pool warm for the duration of one chat.
 *
 * <p>Honors the global {@code vance.ai.cache.enabled} switch
 * (default {@code true}). When set to {@code false}, the provider
 * downgrades any inbound {@link AiChatOptions#getCacheBoundary()} to
 * {@link CacheBoundary#NONE} via {@link #applyOptionGates} before
 * constructing the chat — engines keep using {@code AiChatOptions} as
 * usual, the operator-level kill switch wins.
 *
 * <p>Cross-cutting orchestration lives in {@link AbstractChatProvider}.
 */
@Component
@Slf4j
public class AnthropicProvider extends AbstractChatProvider {

    /** Anthropic's API requires maxTokens; pick a safe upper bound when callers omit it. */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    /** Default Anthropic API host — overridden per call by {@link ProviderListingRequest#baseUrl()}. */
    private static final String DEFAULT_LISTING_BASE_URL = "https://api.anthropic.com";

    /** Anthropic API version header. Matches what the SDK uses internally. */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** Listing API returns up to 1000 models per page — Anthropic only has ~10 today. */
    private static final int LISTING_PAGE_SIZE = 1000;

    private final boolean cacheEnabled;

    public AnthropicProvider(
            ModelCatalog modelCatalog,
            LlmResponseSanitizer responseSanitizer,
            MessageParserRegistry messageParserRegistry,
            @Value("${vance.ai.cache.enabled:true}") boolean cacheEnabled) {
        super(modelCatalog, responseSanitizer, messageParserRegistry);
        this.cacheEnabled = cacheEnabled;
        if (!cacheEnabled) {
            log.info("Anthropic prompt caching DISABLED via vance.ai.cache.enabled=false");
        }
    }

    @Override
    public ProviderType getType() {
        return ProviderType.ANTHROPIC;
    }

    /**
     * Combined option-gate pass: capability-driven thinking downgrade
     * + global cache kill. Both operations return a fresh
     * {@link AiChatOptions} (immutable contract preserved).
     */
    @Override
    protected AiChatOptions applyOptionGates(AiChatOptions options, ModelInfo modelInfo) {
        return applyCapabilityGates(applyGlobalCacheKill(options), modelInfo);
    }

    @Override
    protected BuiltChat buildModels(
            AiChatConfig config, AiChatOptions effective, ModelInfo modelInfo) {
        int maxTokens = effective.getMaxTokens() != null
                ? effective.getMaxTokens()
                : DEFAULT_MAX_TOKENS;
        Duration timeout = Duration.ofSeconds(
                modelInfo.effectiveTimeoutSeconds(effective.getTimeoutSeconds()));
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(config.apiKey())
                .timeout(timeout)
                .build();
        ChatModel sync = new AnthropicDirectChatModel(
                client, config.modelName(), maxTokens, effective);
        StreamingChatModel streaming = new AnthropicDirectStreamingChatModel(
                client, config.modelName(), maxTokens, effective);
        log.debug("Built Anthropic chat: model='{}', maxTokens={}, "
                        + "cacheBoundary={}, ttl={}, thinking={}",
                config.modelName(), maxTokens,
                effective.getCacheBoundary(), effective.getCacheTtl(),
                effective.getThinkingLevel());
        return new BuiltChat(sync, streaming);
    }

    /**
     * Anthropic's {@code GET /v1/models} returns
     * {@code {"data":[{"id":"claude-sonnet-4-5","type":"model","display_name":"...","created_at":"..."}]}}.
     * Context-window isn't returned — Anthropic has no machine-readable
     * source for that today, so the {@link DiscoveredModelInfo#contextWindowTokens}
     * field stays {@code null} and the catalog inherits it from the
     * bundled / manual layer at lookup time.
     */
    @Override
    public List<DiscoveredModelInfo> listAvailableModels(ProviderListingRequest req) {
        String baseUrl = req.baseUrl() != null ? req.baseUrl() : DEFAULT_LISTING_BASE_URL;
        HttpRequest http = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/models?limit=" + LISTING_PAGE_SIZE))
                .header("x-api-key", req.apiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        JsonNode root = ProviderListingHttp.fetchJson(http);
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new RuntimeException("Anthropic listing response missing 'data' array: " + root);
        }
        List<DiscoveredModelInfo> out = new ArrayList<>(data.size());
        for (JsonNode entry : data) {
            String id = entry.path("id").asText();
            if (id.isBlank()) continue;
            out.add(new DiscoveredModelInfo(id, null, "chat"));
        }
        return out;
    }

    /**
     * Drop {@code thinking: high} (or any non-OFF level) when the
     * resolved model does not advertise
     * {@link ModelCapability#THINKING} in {@code ai-models.yaml}. The
     * call would otherwise reach Anthropic's API with a {@code thinking}
     * block on a model that doesn't honour extended thinking — Haiku
     * historically being the common case — and waste a round-trip on
     * a 400. Recipe authors keep asking for the level they want; the
     * catalog is the single point of truth for what is honourable.
     */
    private static AiChatOptions applyCapabilityGates(
            AiChatOptions options, ModelInfo modelInfo) {
        ThinkingLevel requested = options.getThinkingLevel();
        if (requested == null || requested == ThinkingLevel.OFF) {
            return options;
        }
        if (modelInfo.supports(ModelCapability.THINKING)) {
            return options;
        }
        log.debug("Anthropic model '{}/{}' lacks THINKING capability — "
                        + "downgrading requested level {} → OFF for this call",
                modelInfo.provider(), modelInfo.modelName(), requested);
        return options.toBuilder().thinkingLevel(ThinkingLevel.OFF).build();
    }

    /**
     * Force {@link CacheBoundary#NONE} when the global switch is off.
     * Keeps the AiChatOptions immutable contract intact by returning a
     * fresh builder copy rather than mutating the input.
     */
    private AiChatOptions applyGlobalCacheKill(AiChatOptions options) {
        if (cacheEnabled) {
            return options;
        }
        if (options.getCacheBoundary() == CacheBoundary.NONE) {
            return options;
        }
        return options.toBuilder().cacheBoundary(CacheBoundary.NONE).build();
    }
}
