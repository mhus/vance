package de.mhus.vance.brain.ai.ollama;

import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelProvider;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.StandardAiChat;
import de.mhus.vance.brain.ai.ThinkingLevel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ollama backend for {@link AiModelProvider} — self-hosted Ollama
 * servers (default {@code http://localhost:11434}).
 *
 * <p>Builds {@link OllamaChatModel} (sync) and
 * {@link OllamaStreamingChatModel} (streaming) and wraps them in a
 * shared {@link StandardAiChat}. Ollama uses {@code numPredict} where
 * other providers use {@code maxTokens} — translated transparently
 * here.
 *
 * <p>Local Ollama doesn't authenticate. The {@link AiChatConfig#apiKey()}
 * is required by the config record (uniform contract with other
 * providers) but not forwarded to the model. Operators put a
 * placeholder string in the {@code ai.provider.ollama.apiKey} setting.
 */
@Component
@Slf4j
public class OllamaProvider implements AiModelProvider {

    public static final String NAME = ProviderType.OLLAMA.wireName();

    private final String defaultBaseUrl;
    private final ModelCatalog modelCatalog;

    public OllamaProvider(
            ModelCatalog modelCatalog,
            @Value("${vance.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
        this.modelCatalog = modelCatalog;
        this.defaultBaseUrl = baseUrl;
    }

    @Override
    public ProviderType getType() {
        return ProviderType.OLLAMA;
    }

    @Override
    public AiChat createChat(AiChatConfig config, AiChatOptions options) {
        if (!NAME.equals(config.provider())) {
            throw new AiChatException(
                    "OllamaProvider received config for provider '" + config.provider() + "'");
        }
        ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                options.getTenantId(), options.getProjectId(),
                config.providerInstance(), NAME, config.modelName());
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
        try {
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
                            + "numPredict={}, temperature={}, think={}",
                    config.modelName(), baseUrl, numCtx, options.getMaxTokens(),
                    options.getTemperature(), think);
            return new StandardAiChat(
                    config.fullName(),
                    ProviderType.OLLAMA,
                    modelInfo.capabilities(),
                    sync,
                    streaming,
                    options);
        } catch (RuntimeException e) {
            throw new AiChatException(
                    "Failed to build Ollama chat for " + config.fullName(), e);
        }
    }
}
