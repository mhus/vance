package de.mhus.vance.brain.ai.anthropic;

import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelProvider;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Anthropic (Claude) backend for {@link AiModelProvider}.
 *
 * <p>Each {@link #createChat(AiChatConfig, AiChatOptions)} builds a matched
 * pair of sync + streaming Anthropic models with identical parameters.
 * Having both ready lets {@link AiChat#chatModel()} and
 * {@link AiChat#streamingChatModel()} return a ready-to-use langchain4j
 * object without another HTTP-client init on the critical path.
 */
@Component
@Slf4j
public class AnthropicProvider implements AiModelProvider {

    public static final String NAME = "anthropic";

    /** Anthropic's API requires maxTokens; pick a safe upper bound when callers omit it. */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public AiChat createChat(AiChatConfig config, AiChatOptions options) {
        if (!NAME.equals(config.provider())) {
            throw new AiChatException(
                    "AnthropicProvider received config for provider '" + config.provider() + "'");
        }
        int maxTokens = options.getMaxTokens() != null
                ? options.getMaxTokens()
                : DEFAULT_MAX_TOKENS;
        Duration timeout = Duration.ofSeconds(options.getTimeoutSeconds());
        try {
            AnthropicChatModel sync = AnthropicChatModel.builder()
                    .apiKey(config.apiKey())
                    .modelName(config.modelName())
                    .temperature(options.getTemperature())
                    .maxTokens(maxTokens)
                    .timeout(timeout)
                    .logRequests(options.getLogRequests())
                    .logResponses(options.getLogRequests())
                    .build();
            AnthropicStreamingChatModel streaming = AnthropicStreamingChatModel.builder()
                    .apiKey(config.apiKey())
                    .modelName(config.modelName())
                    .temperature(options.getTemperature())
                    .maxTokens(maxTokens)
                    .timeout(timeout)
                    .logRequests(options.getLogRequests())
                    .logResponses(options.getLogRequests())
                    .build();
            log.debug("Built Anthropic chat pair: model='{}', maxTokens={}, temperature={}",
                    config.modelName(), maxTokens, options.getTemperature());
            return new AnthropicChat(config.fullName(), sync, streaming, options);
        } catch (RuntimeException e) {
            throw new AiChatException(
                    "Failed to build Anthropic chat for " + config.fullName(), e);
        }
    }
}
