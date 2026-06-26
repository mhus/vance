package de.mhus.vance.brain.ai.ollama;

import de.mhus.vance.brain.ai.AbstractChatProvider;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import de.mhus.vance.brain.ai.LlmResponseSanitizer;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ProviderListingHttp;
import de.mhus.vance.brain.ai.ProviderListingRequest;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.ThinkingLevel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
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
 * Ollama backend — self-hosted Ollama servers (default
 * {@code http://localhost:11434}).
 *
 * <p>Builds {@link OllamaChatModel} (sync) and
 * {@link OllamaStreamingChatModel} (streaming). Ollama uses
 * {@code numPredict} where other providers use {@code maxTokens} —
 * translated transparently in {@link #buildModels}.
 *
 * <p>Local Ollama doesn't authenticate. The {@link AiChatConfig#apiKey()}
 * is required by the config record (uniform contract with other
 * providers) but not forwarded to the model. Operators put a
 * placeholder string in the {@code ai.provider.ollama.apiKey} setting.
 *
 * <p>Cross-cutting orchestration (config validation,
 * {@link ModelCatalog} lookup, {@link LlmResponseSanitizer}-wired
 * {@code StandardAiChat} construction, {@code AiChatException} wrap)
 * lives in {@link AbstractChatProvider}.
 */
@Component
@Slf4j
public class OllamaProvider extends AbstractChatProvider {

    private final String defaultBaseUrl;

    public OllamaProvider(
            ModelCatalog modelCatalog,
            LlmResponseSanitizer responseSanitizer,
            @Value("${vance.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
        super(modelCatalog, responseSanitizer);
        this.defaultBaseUrl = baseUrl;
    }

    @Override
    public ProviderType getType() {
        return ProviderType.OLLAMA;
    }

    /**
     * Ollama's {@code GET /api/tags} returns
     * {@code {"models":[{"name":"qwen3:30b","model":"qwen3:30b","size":...,
     * "modified_at":"...","details":{"parameter_size":"30B",...}}]}}.
     * Wire-name is the {@code name} field verbatim (carries the
     * {@code <family>:<tag>} convention with the colon literal —
     * downstream slugification handles that). Ollama doesn't surface
     * context-window in this endpoint; that's only visible via
     * {@code /api/show} per model, which we leave to the catalog's
     * bundled / manual layer to provide.
     */
    @Override
    public List<DiscoveredModelInfo> listAvailableModels(ProviderListingRequest req) {
        String baseUrl = req.baseUrl() != null ? req.baseUrl() : defaultBaseUrl;
        HttpRequest http = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        JsonNode root = ProviderListingHttp.fetchJson(http);
        JsonNode models = root.path("models");
        if (!models.isArray()) {
            throw new RuntimeException("Ollama listing response missing 'models' array: " + root);
        }
        List<DiscoveredModelInfo> out = new ArrayList<>(models.size());
        for (JsonNode entry : models) {
            String name = entry.path("name").asText();
            if (name.isBlank()) continue;
            out.add(new DiscoveredModelInfo(name, null, "chat"));
        }
        return out;
    }

    @Override
    protected BuiltChat buildModels(
            AiChatConfig config, AiChatOptions options, ModelInfo modelInfo) {
        Duration timeout = Duration.ofSeconds(
                modelInfo.effectiveTimeoutSeconds(options.getTimeoutSeconds()));
        boolean think = options.getThinkingLevel() != ThinkingLevel.OFF;
        Integer seed = options.getSeed() == null ? null : options.getSeed().intValue();
        // Ollama defaults num_ctx=4096 unless overridden — that's far
        // below the ai-test session sizes (~50K tokens). Pass the
        // catalog's contextWindowTokens so the model is loaded with
        // its real window; YAML overrides cap RAM cost.
        int numCtx = modelInfo.contextWindowTokens();
        // Per-tenant override (custom Ollama host) wins over the Spring
        // boot-time default. Empty / unset falls back to vance.ai.ollama.base-url.
        String baseUrl = config.baseUrl() != null ? config.baseUrl() : defaultBaseUrl;
        OllamaChatModel sync = OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(config.modelName())
                .temperature(options.getTemperature())
                .numCtx(numCtx)
                .numPredict(options.getMaxTokens())
                .topP(options.getTopP())
                .topK(options.getTopK())
                .seed(seed)
                .stop(options.getStopSequences())
                .timeout(timeout)
                .think(think)
                .returnThinking(think)
                .logRequests(options.getLogRequests())
                .logResponses(options.getLogRequests())
                .build();
        OllamaStreamingChatModel streaming = OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(config.modelName())
                .temperature(options.getTemperature())
                .numCtx(numCtx)
                .numPredict(options.getMaxTokens())
                .topP(options.getTopP())
                .topK(options.getTopK())
                .seed(seed)
                .stop(options.getStopSequences())
                .timeout(timeout)
                .think(think)
                .returnThinking(think)
                .logRequests(options.getLogRequests())
                .logResponses(options.getLogRequests())
                .build();
        log.debug("Built Ollama chat pair: model='{}', baseUrl='{}', numCtx={}, "
                        + "numPredict={}, temperature={}, think={}, stripThinkTags={}",
                config.modelName(), baseUrl, numCtx, options.getMaxTokens(),
                options.getTemperature(), think, modelInfo.stripThinkTags());
        return new BuiltChat(sync, streaming);
    }
}
