package de.mhus.vance.brain.ai.gemini;

import de.mhus.vance.brain.ai.AbstractChatProvider;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
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
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Google Gemini backend.
 *
 * <p>Builds {@link GoogleAiGeminiChatModel} (sync) +
 * {@link GoogleAiGeminiStreamingChatModel} (streaming). Gemini field
 * names differ slightly from Anthropic's (maxOutputTokens,
 * logRequestsAndResponses) — handled here, hidden from callers.
 *
 * <p><b>Prompt caching.</b> Gemini 2.5+ enables <i>implicit</i> caching
 * server-side by default — no client marker, no setup. The cache hits
 * automatically when a request shares a prefix with a recent one
 * (≥ 1024 tokens for Flash, 4096 for Pro). There is intentionally
 * <b>no client-side lever</b> here: the per-call
 * {@link AiChatOptions#getCacheBoundary()} and
 * {@link AiChatOptions#getCacheTtl()} are silently ignored — Gemini
 * doesn't expose comparable controls.
 *
 * <p>The kill-switch {@code vance.ai.cache.enabled=false} is therefore
 * unenforceable here and not consulted; Anthropic and OpenAI still
 * honour it.
 *
 * <p>Cross-cutting orchestration lives in {@link AbstractChatProvider}.
 */
@Component
@Slf4j
public class GeminiProvider extends AbstractChatProvider {

    public GeminiProvider(
            ModelCatalog modelCatalog,
            LlmResponseSanitizer responseSanitizer,
            MessageParserRegistry messageParserRegistry) {
        super(modelCatalog, responseSanitizer, messageParserRegistry);
    }

    @Override
    public ProviderType getType() {
        return ProviderType.GEMINI;
    }

    @Override
    protected BuiltChat buildModels(
            AiChatConfig config, AiChatOptions options, ModelInfo modelInfo) {
        Duration timeout = Duration.ofSeconds(
                modelInfo.effectiveTimeoutSeconds(options.getTimeoutSeconds()));
        ThinkingLevel effectiveLevel = gateThinkingLevel(
                options.getThinkingLevel(), modelInfo);
        @Nullable GeminiThinkingConfig thinking = mapThinking(effectiveLevel);
        Integer seed = options.getSeed() == null ? null : options.getSeed().intValue();
        GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder syncBuilder =
                GoogleAiGeminiChatModel.builder()
                        .apiKey(config.apiKey())
                        .modelName(config.modelName())
                        .temperature(options.getTemperature())
                        .maxOutputTokens(options.getMaxTokens())
                        .topP(options.getTopP())
                        .topK(options.getTopK())
                        .frequencyPenalty(options.getFrequencyPenalty())
                        .presencePenalty(options.getPresencePenalty())
                        .seed(seed)
                        .stopSequences(options.getStopSequences())
                        .timeout(timeout)
                        .logRequestsAndResponses(options.getLogRequests());
        GoogleAiGeminiStreamingChatModel.GoogleAiGeminiStreamingChatModelBuilder streamBuilder =
                GoogleAiGeminiStreamingChatModel.builder()
                        .apiKey(config.apiKey())
                        .modelName(config.modelName())
                        .temperature(options.getTemperature())
                        .maxOutputTokens(options.getMaxTokens())
                        .topP(options.getTopP())
                        .topK(options.getTopK())
                        .frequencyPenalty(options.getFrequencyPenalty())
                        .presencePenalty(options.getPresencePenalty())
                        .seed(seed)
                        .stopSequences(options.getStopSequences())
                        .timeout(timeout)
                        .logRequestsAndResponses(options.getLogRequests());
        if (thinking != null) {
            syncBuilder.thinkingConfig(thinking);
            streamBuilder.thinkingConfig(thinking);
        }
        log.debug("Built Gemini chat pair: model='{}', maxOutputTokens={}, "
                        + "temperature={}, thinkingLevel={}",
                config.modelName(), options.getMaxTokens(),
                options.getTemperature(), options.getThinkingLevel());
        return new BuiltChat(syncBuilder.build(), streamBuilder.build());
    }

    /**
     * Default Gemini API host — overridden per call by
     * {@link ProviderListingRequest#baseUrl()}.
     */
    private static final String DEFAULT_LISTING_BASE_URL = "https://generativelanguage.googleapis.com";

    /**
     * Gemini's {@code GET /v1beta/models?key=...&pageSize=200} returns
     * {@code {"models":[{"name":"models/gemini-2.0-flash","inputTokenLimit":...,
     * "outputTokenLimit":..., "supportedGenerationMethods":["generateContent",...]}]}}.
     * Wire name comes prefixed with {@code "models/"} which we strip;
     * {@code inputTokenLimit} maps cleanly to
     * {@link DiscoveredModelInfo#contextWindowTokens}. Models that
     * don't advertise {@code generateContent} are skipped (embedding,
     * tuning endpoints, …) — we only want chat-capable entries here.
     */
    @Override
    public List<DiscoveredModelInfo> listAvailableModels(ProviderListingRequest req) {
        String baseUrl = req.baseUrl() != null ? req.baseUrl() : DEFAULT_LISTING_BASE_URL;
        String key = URLEncoder.encode(req.apiKey(), StandardCharsets.UTF_8);
        HttpRequest http = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1beta/models?key=" + key + "&pageSize=200"))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        JsonNode root = ProviderListingHttp.fetchJson(http);
        JsonNode models = root.path("models");
        if (!models.isArray()) {
            throw new RuntimeException("Gemini listing response missing 'models' array: " + root);
        }
        List<DiscoveredModelInfo> out = new ArrayList<>(models.size());
        for (JsonNode entry : models) {
            String name = entry.path("name").asText();
            if (name.isBlank()) continue;
            String wireName = name.startsWith("models/") ? name.substring("models/".length()) : name;
            JsonNode methods = entry.path("supportedGenerationMethods");
            if (methods.isArray() && methods.size() > 0 && !supportsChat(methods)) {
                // Embedding / tuning endpoint — out of scope for this catalog.
                continue;
            }
            Integer ctx = null;
            if (entry.has("inputTokenLimit") && entry.path("inputTokenLimit").canConvertToInt()) {
                ctx = entry.path("inputTokenLimit").asInt();
            }
            out.add(new DiscoveredModelInfo(wireName, ctx, "chat"));
        }
        return out;
    }

    private static boolean supportsChat(JsonNode methods) {
        for (JsonNode m : methods) {
            if ("generateContent".equals(m.asText())) return true;
        }
        return false;
    }

    /**
     * Drop the requested thinking level to {@link ThinkingLevel#OFF}
     * when the resolved model does not advertise
     * {@link ModelCapability#THINKING} in {@code ai-models.yaml}. The
     * recipe author asks for {@code thinking: high}; whether the model
     * can actually honour that with the current SDK/API combination is
     * a catalog decision, not a recipe decision. Without this gate
     * Google's API returns {@code 400 "Thinking level is not supported
     * for this model"} as soon as we hit a Gemini build whose
     * {@code thinkingConfig} contract diverged from langchain4j's
     * {@code thinkingLevel} call, breaking every spawn.
     */
    static ThinkingLevel gateThinkingLevel(
            @Nullable ThinkingLevel requested, ModelInfo modelInfo) {
        if (requested == null || requested == ThinkingLevel.OFF) {
            return ThinkingLevel.OFF;
        }
        if (modelInfo.supports(ModelCapability.THINKING)) {
            return requested;
        }
        log.debug("Gemini model '{}/{}' lacks THINKING capability — "
                        + "downgrading requested level {} → OFF for this call",
                modelInfo.provider(), modelInfo.modelName(), requested);
        return ThinkingLevel.OFF;
    }

    /**
     * Map a {@link ThinkingLevel} to a {@link GeminiThinkingConfig}, or
     * {@code null} when no config should be set on the builder (the
     * default for {@link ThinkingLevel#OFF}). Gemini 2.5+ models honor
     * MINIMAL/LOW/MEDIUM/HIGH; older models reject the field with an
     * API error — that's a recipe/model mismatch and should fail loudly.
     */
    static @Nullable GeminiThinkingConfig mapThinking(ThinkingLevel level) {
        if (level == null || level == ThinkingLevel.OFF) {
            return null;
        }
        GeminiThinkingConfig.GeminiThinkingLevel native_ = switch (level) {
            case MINIMAL -> GeminiThinkingConfig.GeminiThinkingLevel.MINIMAL;
            case LOW -> GeminiThinkingConfig.GeminiThinkingLevel.LOW;
            case MEDIUM -> GeminiThinkingConfig.GeminiThinkingLevel.MEDIUM;
            case HIGH -> GeminiThinkingConfig.GeminiThinkingLevel.HIGH;
            case OFF -> throw new IllegalStateException("OFF handled above");
        };
        return GeminiThinkingConfig.builder()
                .thinkingLevel(native_)
                .build();
    }
}
