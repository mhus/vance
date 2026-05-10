package de.mhus.vance.brain.ai.openai;

import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelProvider;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.StandardAiChat;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
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
 */
@Component
@Slf4j
public class OpenAiProvider implements AiModelProvider {

    public static final String NAME = ProviderType.OPENAI.wireName();

    private final String baseUrl;

    public OpenAiProvider(
            @Value("${vance.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl) {
        this.baseUrl = baseUrl;
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
        try {
            OpenAiChatModel sync = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(config.apiKey())
                    .modelName(config.modelName())
                    .temperature(options.getTemperature())
                    .maxTokens(options.getMaxTokens())
                    .timeout(timeout)
                    .logRequests(options.getLogRequests())
                    .logResponses(options.getLogRequests())
                    .build();
            OpenAiStreamingChatModel streaming = OpenAiStreamingChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(config.apiKey())
                    .modelName(config.modelName())
                    .temperature(options.getTemperature())
                    .maxTokens(options.getMaxTokens())
                    .timeout(timeout)
                    .logRequests(options.getLogRequests())
                    .logResponses(options.getLogRequests())
                    .build();
            log.debug("Built OpenAI chat pair: model='{}', baseUrl='{}', maxTokens={}, temperature={}",
                    config.modelName(), baseUrl, options.getMaxTokens(), options.getTemperature());
            return new StandardAiChat(
                    config.fullName(), ProviderType.OPENAI, sync, streaming, options);
        } catch (RuntimeException e) {
            throw new AiChatException(
                    "Failed to build OpenAI chat for " + config.fullName(), e);
        }
    }
}
