package de.mhus.vance.brain.ai.gemini;

import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelProvider;
import de.mhus.vance.brain.ai.StandardAiChat;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Google Gemini backend for {@link AiModelProvider}.
 *
 * <p>Builds {@link GoogleAiGeminiChatModel} (sync) and
 * {@link GoogleAiGeminiStreamingChatModel} (streaming) with identical
 * parameters, wraps them in a shared {@link StandardAiChat}. Gemini field
 * names differ slightly from Anthropic's (maxOutputTokens,
 * logRequestsAndResponses) — handled here, hidden from callers.
 */
@Component
@Slf4j
public class GeminiProvider implements AiModelProvider {

    public static final String NAME = "gemini";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public AiChat createChat(AiChatConfig config, AiChatOptions options) {
        if (!NAME.equals(config.provider())) {
            throw new AiChatException(
                    "GeminiProvider received config for provider '" + config.provider() + "'");
        }
        Duration timeout = Duration.ofSeconds(options.getTimeoutSeconds());
        try {
            GoogleAiGeminiChatModel sync = GoogleAiGeminiChatModel.builder()
                    .apiKey(config.apiKey())
                    .modelName(config.modelName())
                    .temperature(options.getTemperature())
                    .maxOutputTokens(options.getMaxTokens())
                    .timeout(timeout)
                    .logRequestsAndResponses(options.getLogRequests())
                    .build();
            GoogleAiGeminiStreamingChatModel streaming = GoogleAiGeminiStreamingChatModel.builder()
                    .apiKey(config.apiKey())
                    .modelName(config.modelName())
                    .temperature(options.getTemperature())
                    .maxOutputTokens(options.getMaxTokens())
                    .timeout(timeout)
                    .logRequestsAndResponses(options.getLogRequests())
                    .build();
            log.debug("Built Gemini chat pair: model='{}', maxOutputTokens={}, temperature={}",
                    config.modelName(), options.getMaxTokens(), options.getTemperature());
            return new StandardAiChat(config.fullName(), sync, streaming, options);
        } catch (RuntimeException e) {
            throw new AiChatException(
                    "Failed to build Gemini chat for " + config.fullName(), e);
        }
    }
}
