package de.mhus.vance.brain.ai.ollamacloud;

import de.mhus.vance.brain.ai.AbstractChatProvider;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.DiscoveredModelInfo;
import de.mhus.vance.brain.ai.LlmResponseSanitizer;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.parser.MessageParserRegistry;
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
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Ollama Cloud backend — talks to {@code https://ollama.com} using the
 * Ollama wire protocol with bearer authentication.
 *
 * <p>Uses the same {@link OllamaChatModel} / {@link OllamaStreamingChatModel}
 * pair as the self-hosted {@link de.mhus.vance.brain.ai.ollama.OllamaProvider}.
 * The API key is injected as {@code Authorization: Bearer <key>} via
 * {@code customHeaders} since the Ollama SDK doesn't have a first-class
 * {@code apiKey} field.
 *
 * <p>Cross-cutting orchestration lives in {@link AbstractChatProvider}.
 */
@Component
@Slf4j
public class OllamaCloudProvider extends AbstractChatProvider {

    private final String baseUrl;

    public OllamaCloudProvider(
            ModelCatalog modelCatalog,
            LlmResponseSanitizer responseSanitizer,
            MessageParserRegistry messageParserRegistry,
            @Value("${vance.ai.ollama-cloud.base-url:https://ollama.com}") String baseUrl) {
        super(modelCatalog, responseSanitizer, messageParserRegistry);
        this.baseUrl = baseUrl;
    }

    @Override
    public ProviderType getType() {
        return ProviderType.OLLAMA_CLOUD;
    }

    /**
     * Ollama Cloud exposes the same {@code /api/tags} shape as
     * self-hosted Ollama; the only difference is the bearer token in
     * the {@code Authorization} header.
     */
    @Override
    public List<DiscoveredModelInfo> listAvailableModels(ProviderListingRequest req) {
        String base = req.baseUrl() != null ? req.baseUrl() : baseUrl;
        HttpRequest http = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/tags"))
                .header("Authorization", "Bearer " + req.apiKey())
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        JsonNode root = ProviderListingHttp.fetchJson(http);
        JsonNode models = root.path("models");
        if (!models.isArray()) {
            throw new RuntimeException(
                    "Ollama Cloud listing response missing 'models' array: " + root);
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
        Map<String, String> authHeader = Map.of("Authorization", "Bearer " + config.apiKey());
        boolean think = options.getThinkingLevel() != ThinkingLevel.OFF;
        Integer seed = options.getSeed() == null ? null : options.getSeed().intValue();
        OllamaChatModel sync = OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(config.modelName())
                .temperature(options.getTemperature())
                .numPredict(options.getMaxTokens())
                .topP(options.getTopP())
                .topK(options.getTopK())
                .seed(seed)
                .stop(options.getStopSequences())
                .timeout(timeout)
                .customHeaders(authHeader)
                .think(think)
                .returnThinking(think)
                .logRequests(options.getLogRequests())
                .logResponses(options.getLogRequests())
                .build();
        OllamaStreamingChatModel streaming = OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(config.modelName())
                .temperature(options.getTemperature())
                .numPredict(options.getMaxTokens())
                .topP(options.getTopP())
                .topK(options.getTopK())
                .seed(seed)
                .stop(options.getStopSequences())
                .timeout(timeout)
                .customHeaders(authHeader)
                .think(think)
                .returnThinking(think)
                .logRequests(options.getLogRequests())
                .logResponses(options.getLogRequests())
                .build();
        log.debug("Built Ollama Cloud chat pair: model='{}', baseUrl='{}', numPredict={}, "
                        + "temperature={}, think={}",
                config.modelName(), baseUrl, options.getMaxTokens(),
                options.getTemperature(), think);
        return new BuiltChat(sync, streaming);
    }
}
