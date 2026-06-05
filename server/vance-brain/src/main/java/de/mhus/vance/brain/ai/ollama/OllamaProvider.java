package de.mhus.vance.brain.ai.ollama;

import de.mhus.vance.brain.ai.AbstractChatProvider;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.LlmResponseSanitizer;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.ThinkingLevel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
