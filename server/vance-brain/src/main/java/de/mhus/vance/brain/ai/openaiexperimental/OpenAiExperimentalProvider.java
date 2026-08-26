package de.mhus.vance.brain.ai.openaiexperimental;

import de.mhus.vance.brain.ai.AbstractChatProvider;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.CacheBoundary;
import de.mhus.vance.brain.ai.CacheTtl;
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
import de.mhus.vance.brain.ai.UsageSink;
import de.mhus.vance.brain.ai.openai.OpenAiProvider;
import de.mhus.vance.brain.ai.openai.ToolCallContentHttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * OpenAI Responses-API backend ({@code /v1/responses}).
 *
 * <p><b>Experimental, temporary bridge.</b> Reasoning-native OpenAI
 * models ({@code gpt-5.6-sol} and siblings) reject the combination
 * of {@code reasoning_effort} with function tools on
 * {@code /v1/chat/completions} with HTTP 400, so they are unusable
 * for agentic turns on the legacy endpoint handled by
 * {@link OpenAiProvider}. The Responses-API accepts that combination,
 * and langchain4j 1.18.1 ships {@code OpenAiResponsesChatModel} /
 * {@code OpenAiResponsesStreamingChatModel} (both {@code @Experimental})
 * that speak it. This provider wires those into Vance's
 * {@link AbstractChatProvider} template so the {@code gpt-5.6-*}
 * family finally runs <i>with</i> reasoning on agentic turns.
 *
 * <p>The provider disappears once upstream removes the need for it —
 * either when {@code OpenAiChatModel} itself speaks
 * {@code /v1/responses}, or when the Responses client leaves
 * {@code @Experimental}. See {@code readme/openai-experimental-provider.md}
 * for the full rationale, the migration path, and the verifiable
 * failure that motivated it.
 *
 * <p><b>Base URL.</b> Configurable via {@code vance.ai.openai-experimental.base-url},
 * defaulting to OpenAI proper. Tenants select this backend per
 * model instance via {@code ai.provider.<instance>.type=openai-experimental}.
 *
 * <p><b>Prompt caching.</b> Unlike {@link OpenAiProvider}, which sends
 * cache hints as custom parameters on the chat-completions request, the
 * Responses client exposes {@code promptCacheKey(...)} and
 * {@code promptCacheRetention(...)} as native builder methods — same
 * semantics (sticky routing + extended retention), typed surface. The
 * global {@code vance.ai.cache.enabled} switch and the per-call
 * {@link CacheBoundary} gate it identically.
 *
 * <p><b>Reasoning summary.</b> {@code OpenAiResponsesClient} maps the
 * reasoning summary onto {@code AiMessage.thinking()} automatically
 * when {@code reasoningSummary} is set — the same field Foot's
 * "💭 thoughts" block reads, so no client-side change is needed. We
 * request {@code "auto"} only when reasoning is actually on; an
 * explicit {@code reasoning_effort: "none"} (the off-value the
 * {@code gpt-5*} quirk rule pins) carries no reasoning and gets no
 * summary.
 *
 * <p>Cross-cutting orchestration (accounting, sanitizer, parser,
 * system-message merge, unsupported-params strip) lives in
 * {@link AbstractChatProvider} and is inherited verbatim.
 */
@Component
@Slf4j
public class OpenAiExperimentalProvider extends AbstractChatProvider {

    /** OpenAI proper — used whenever nothing else is configured. */
    public static final String OPENAI_EXPERIMENTAL_BASE_URL = "https://api.openai.com/v1";

    /**
     * Reasoning-summary mode requested when reasoning is on. {@code "auto"}
     * is OpenAI's default — the model decides whether a summary helps and
     * how long it should be. Surfaced through {@code AiMessage.thinking()}
     * by the Responses client, so Foot's thoughts block fills without a
     * client-side change.
     */
    public static final String REASONING_SUMMARY_AUTO = "auto";

    /**
     * Reasoning-effort value that means "do not reason". The {@code gpt-5*}
     * quirk rule pins this as the off-value so a recipe without a
     * {@code thinking: ...} param still sends an explicit {@code "none"}
     * rather than relying on the endpoint default. Same string the legacy
     * OpenAI provider uses — kept as a constant here so the test asserts
     * against the literal the Responses API actually receives.
     */
    private static final String REASONING_EFFORT_NONE = "none";

    private final String defaultBaseUrl;
    private final boolean cacheEnabled;

    public OpenAiExperimentalProvider(
            ModelCatalog modelCatalog,
            LlmResponseSanitizer responseSanitizer,
            MessageParserRegistry messageParserRegistry,
            UsageSink usageSink,
            @Value("${vance.ai.openai-experimental.base-url:}") String baseUrl,
            @Value("${vance.ai.cache.enabled:true}") boolean cacheEnabled) {
        super(modelCatalog, responseSanitizer, messageParserRegistry, usageSink);
        this.defaultBaseUrl = StringUtils.isBlank(baseUrl)
                ? OPENAI_EXPERIMENTAL_BASE_URL : baseUrl.trim();
        this.cacheEnabled = cacheEnabled;
    }

    @Override
    public ProviderType getType() {
        return ProviderType.OPENAI_EXPERIMENTAL;
    }

    @Override
    protected BuiltChat buildModels(
            AiChatConfig config, AiChatOptions options, ModelInfo modelInfo) {
        Duration timeout = Duration.ofSeconds(
                modelInfo.effectiveTimeoutSeconds(options.getTimeoutSeconds()));
        // Streaming gets a generous total-request budget — a healthy
        // streamed generation runs far longer than a single sync response
        // and must not be cut off at the sync timeout. Same rationale as
        // OpenAiProvider: a 2026-07-29 deepseek-v4-pro incident cut a
        // healthy stream at the sync cap.
        Duration streamTimeout = Duration.ofSeconds(
                modelInfo.scaledStreamTimeoutSeconds(
                        options.getTimeoutSeconds(), options.getEstInputTokens()));
        // Per-tenant override (gateway in front of OpenAI) wins over the
        // Spring boot-time default. Empty / unset falls back to the
        // configured default.
        String baseUrl = config.baseUrl() != null ? config.baseUrl() : defaultBaseUrl;

        // The Responses builder has no .timeout(...) convenience — unlike
        // OpenAiChatModel, which wires the timeout into a default JDK
        // client internally. We configure the client builder directly,
        // matching OpenAiChatModel's defaults: connectTimeout = timeout
        // (fallback 15s), readTimeout = timeout (sync) / streamTimeout
        // (streaming).
        Duration connectTimeout = timeout.getSeconds() > 0 ? timeout : Duration.ofSeconds(15);

        ThinkingLevel effectiveLevel = OpenAiProvider.gateThinkingLevel(
                options.getThinkingLevel(), modelInfo);
        String reasoningEffort = OpenAiProvider.mapReasoningEffort(effectiveLevel);
        if (reasoningEffort == null) {
            // "No reasoning" is normally the absence of the field. A
            // reasoning-native model reasons anyway unless told off —
            // the gpt-5* quirk rule supplies "none" so the off-state is
            // explicit. On the Responses endpoint "none" is a valid
            // value (no crash), and carrying no summary (below) keeps
            // the wire clean. See ModelInfo.reasoningEffortWhenOff().
            reasoningEffort = modelInfo.reasoningEffortWhenOff();
        }
        // Only request a reasoning summary when reasoning is actually
        // on. An explicit "none" carries no chain-of-thought, so asking
        // for a summary would be noise (and may return empty).
        String reasoningSummary =
                (reasoningEffort != null && !REASONING_EFFORT_NONE.equals(reasoningEffort))
                        ? REASONING_SUMMARY_AUTO
                        : null;

        Map<String, Object> cacheParams = buildCacheParameters(config, options, cacheEnabled);
        String promptCacheKey = (String) cacheParams.get("promptCacheKey");
        String promptCacheRetention = (String) cacheParams.get("promptCacheRetention");

        OpenAiResponsesChatModel.Builder syncBuilder = OpenAiResponsesChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .temperature(options.getTemperature())
                .topP(options.getTopP())
                .maxOutputTokens(options.getMaxTokens())
                .reasoningEffort(reasoningEffort)
                .reasoningSummary(reasoningSummary)
                .promptCacheKey(promptCacheKey)
                .promptCacheRetention(promptCacheRetention)
                .httpClientBuilder(httpClientBuilder(connectTimeout, timeout))
                .logRequests(options.getLogRequests())
                .logResponses(options.getLogRequests());
        OpenAiResponsesStreamingChatModel.Builder streamBuilder =
                OpenAiResponsesStreamingChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(config.apiKey())
                        .modelName(config.modelName())
                        .temperature(options.getTemperature())
                        .topP(options.getTopP())
                        .maxOutputTokens(options.getMaxTokens())
                        .reasoningEffort(reasoningEffort)
                        .reasoningSummary(reasoningSummary)
                        .promptCacheKey(promptCacheKey)
                        .promptCacheRetention(promptCacheRetention)
                        .httpClientBuilder(httpClientBuilder(connectTimeout, streamTimeout))
                        .logRequests(options.getLogRequests())
                        .logResponses(options.getLogRequests());
        log.debug("Built OpenAI-experimental chat pair: model='{}', baseUrl='{}', "
                        + "maxOutputTokens={}, temperature={}, reasoningEffort={}, "
                        + "reasoningSummary={}, cacheParams={}",
                config.modelName(), baseUrl, options.getMaxTokens(),
                options.getTemperature(), reasoningEffort, reasoningSummary,
                cacheParams.keySet());
        return new BuiltChat(syncBuilder.build(), streamBuilder.build());
    }

    /**
     * Fresh {@link HttpClientBuilder} with timeouts applied. The
     * {@link ToolCallContentHttpClientBuilder} wraps the classpath
     * default (JDK client) and additionally strips assistant tool-call
     * {@code content: null} before the wire — same hardening as the
     * legacy OpenAI provider, because strict OpenAI-compatible gateways
     * reject that shape on the Responses endpoint too.
     *
     * <p>A fresh instance per call is required: langchain4j mutates the
     * builder's timeout fields, so a shared instance would race across
     * concurrently built models. See
     * {@link ToolCallContentHttpClientBuilder#wrappingDefault()}.
     */
    private static HttpClientBuilder httpClientBuilder(
            Duration connectTimeout, Duration readTimeout) {
        return ToolCallContentHttpClientBuilder.wrappingDefault()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout);
    }

    /**
     * OpenAI's {@code GET /v1/models} returns the same shape on the
     * Responses endpoint as on chat-completions — a {@code data} array
     * of {@code {id, ...}} objects. Shared route, identical parser.
     * {@code contextWindowTokens} and {@code kind} are not in the
     * response; both stay unknown until a manual catalog entry fills
     * them, exactly as for the legacy provider.
     */
    @Override
    public List<DiscoveredModelInfo> listAvailableModels(ProviderListingRequest req) {
        String base = req.baseUrl() != null ? req.baseUrl() : defaultBaseUrl;
        String modelsUrl = base.endsWith("/v1") ? base + "/models" : base + "/v1/models";
        HttpRequest http = HttpRequest.newBuilder()
                .uri(URI.create(modelsUrl))
                .header("Authorization", "Bearer " + req.apiKey())
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        JsonNode root = ProviderListingHttp.fetchJson(http);
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new RuntimeException(
                    "OpenAI-experimental listing response missing 'data' array: " + root);
        }
        List<DiscoveredModelInfo> out = new ArrayList<>(data.size());
        for (JsonNode entry : data) {
            String id = entry.path("id").asText();
            if (id.isBlank()) continue;
            out.add(DiscoveredModelInfo.of(id));
        }
        return out;
    }

    /**
     * Build the {@code prompt_cache_key} + {@code prompt_cache_retention}
     * pair for the Responses-API native builder methods. Empty when
     * caching is disabled by the global switch or by the per-call
     * boundary. Package-private + static so unit tests can pin the
     * mapping without standing the bean up.
     *
     * <p>Wire-identical to {@link OpenAiProvider#buildCacheParameters}
     * — the cache semantics are the same endpoint feature, just exposed
     * through typed builder methods here instead of custom parameters.
     * The key is derived the same way so a tenant switching an instance
     * from {@code openai} to {@code openai-experimental} keeps its
     * existing cache locality.
     */
    static Map<String, Object> buildCacheParameters(
            AiChatConfig config, AiChatOptions options, boolean cacheEnabled) {
        if (!cacheEnabled || options.getCacheBoundary() == CacheBoundary.NONE) {
            return Map.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("promptCacheKey", deriveCacheKey(config));
        if (options.getCacheTtl() == CacheTtl.LONG_1H) {
            params.put("promptCacheRetention", "24h");
        }
        return params;
    }

    /**
     * Derive a stable per-tenant-and-model cache key. Hashing rather
     * than passing the API key directly so the value can appear in
     * logs / traces without leaking the credential. Identical to
     * {@link OpenAiProvider#deriveCacheKey} so switching an instance
     * between the two OpenAI providers preserves cache locality.
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
