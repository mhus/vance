package de.mhus.vance.brain.ai.ollamacloud;

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
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ollama Cloud backend for {@link AiModelProvider} — talks to
 * {@code https://ollama.com} using the Ollama wire protocol with bearer
 * authentication.
 *
 * <p>Uses the same {@link OllamaChatModel} / {@link OllamaStreamingChatModel}
 * pair as the self-hosted {@link de.mhus.vance.brain.ai.ollama.OllamaProvider}.
 * The API key is injected as {@code Authorization: Bearer <key>} via
 * {@code customHeaders} since the Ollama SDK doesn't have a first-class
 * {@code apiKey} field.
 */
@Component
@Slf4j
public class OllamaCloudProvider implements AiModelProvider {

    public static final String NAME = ProviderType.OLLAMA_CLOUD.wireName();

    private final String baseUrl;
    private final ModelCatalog modelCatalog;

    public OllamaCloudProvider(
            ModelCatalog modelCatalog,
            @Value("${vance.ai.ollama-cloud.base-url:https://ollama.com}") String baseUrl) {
        this.modelCatalog = modelCatalog;
        this.baseUrl = baseUrl;
    }

    @Override
    public ProviderType getType() {
        return ProviderType.OLLAMA_CLOUD;
    }

    @Override
    public AiChat createChat(AiChatConfig config, AiChatOptions options) {
        if (!NAME.equals(config.provider())) {
            throw new AiChatException(
                    "OllamaCloudProvider received config for provider '" + config.provider() + "'");
        }
        ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                options.getTenantId(), options.getProjectId(),
                NAME, config.modelName());
        Duration timeout = Duration.ofSeconds(
                modelInfo.effectiveTimeoutSeconds(options.getTimeoutSeconds()));
        Map<String, String> authHeader = Map.of("Authorization", "Bearer " + config.apiKey());
        boolean think = options.getThinkingLevel() != ThinkingLevel.OFF;
        Integer seed = options.getSeed() == null ? null : options.getSeed().intValue();
        try {
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
            return new StandardAiChat(
                    config.fullName(),
                    ProviderType.OLLAMA_CLOUD,
                    modelInfo.capabilities(),
                    sync,
                    streaming,
                    options);
        } catch (RuntimeException e) {
            throw new AiChatException(
                    "Failed to build Ollama Cloud chat for " + config.fullName(), e);
        }
    }
}
