package de.mhus.vance.brain.ai.gemini;

import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelProvider;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.StandardAiChat;
import de.mhus.vance.brain.ai.ThinkingLevel;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Google Gemini backend for {@link AiModelProvider}.
 *
 * <p>Builds {@link GoogleAiGeminiChatModel} (sync) and
 * {@link GoogleAiGeminiStreamingChatModel} (streaming) with identical
 * parameters, wraps them in a shared {@link StandardAiChat}. Gemini field
 * names differ slightly from Anthropic's (maxOutputTokens,
 * logRequestsAndResponses) — handled here, hidden from callers.
 *
 * <p><b>Prompt caching.</b> Gemini 2.5+ enables <i>implicit</i> caching
 * server-side by default — no client marker, no setup. The cache hits
 * automatically when a request shares a prefix with a recent one
 * (≥ 1024 tokens for Flash, 4096 for Pro). There is intentionally
 * <b>no client-side lever</b> here: the per-call
 * {@link de.mhus.vance.brain.ai.AiChatOptions#getCacheBoundary()} and
 * {@link de.mhus.vance.brain.ai.AiChatOptions#getCacheTtl()} are
 * silently ignored — Gemini doesn't expose comparable controls. The
 * explicit {@code cachedContents.create} API exists but requires its
 * own resource lifecycle (TTL, refresh, delete) and is impractical for
 * Vance's short-lived sessions with dynamic system prompts.
 *
 * <p>The kill-switch {@code vance.ai.cache.enabled=false} is therefore
 * unenforceable here and not consulted; Anthropic and OpenAI still
 * honour it.
 */
@Component
@Slf4j
public class GeminiProvider implements AiModelProvider {

    public static final String NAME = ProviderType.GEMINI.wireName();

    private final ModelCatalog modelCatalog;

    public GeminiProvider(ModelCatalog modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

    @Override
    public ProviderType getType() {
        return ProviderType.GEMINI;
    }

    @Override
    public AiChat createChat(AiChatConfig config, AiChatOptions options) {
        if (!NAME.equals(config.provider())) {
            throw new AiChatException(
                    "GeminiProvider received config for provider '" + config.provider() + "'");
        }
        Duration timeout = Duration.ofSeconds(options.getTimeoutSeconds());
        @Nullable GeminiThinkingConfig thinking = mapThinking(options.getThinkingLevel());
        try {
            GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder syncBuilder =
                    GoogleAiGeminiChatModel.builder()
                            .apiKey(config.apiKey())
                            .modelName(config.modelName())
                            .temperature(options.getTemperature())
                            .maxOutputTokens(options.getMaxTokens())
                            .timeout(timeout)
                            .logRequestsAndResponses(options.getLogRequests());
            GoogleAiGeminiStreamingChatModel.GoogleAiGeminiStreamingChatModelBuilder streamBuilder =
                    GoogleAiGeminiStreamingChatModel.builder()
                            .apiKey(config.apiKey())
                            .modelName(config.modelName())
                            .temperature(options.getTemperature())
                            .maxOutputTokens(options.getMaxTokens())
                            .timeout(timeout)
                            .logRequestsAndResponses(options.getLogRequests());
            if (thinking != null) {
                syncBuilder.thinkingConfig(thinking);
                streamBuilder.thinkingConfig(thinking);
            }
            GoogleAiGeminiChatModel sync = syncBuilder.build();
            GoogleAiGeminiStreamingChatModel streaming = streamBuilder.build();
            log.debug("Built Gemini chat pair: model='{}', maxOutputTokens={}, "
                            + "temperature={}, thinkingLevel={}",
                    config.modelName(), options.getMaxTokens(),
                    options.getTemperature(), options.getThinkingLevel());
            return new StandardAiChat(
                    config.fullName(),
                    ProviderType.GEMINI,
                    modelCatalog.lookupOrDefault(
                            options.getTenantId(), options.getProjectId(),
                            NAME, config.modelName()).capabilities(),
                    sync,
                    streaming,
                    options);
        } catch (RuntimeException e) {
            throw new AiChatException(
                    "Failed to build Gemini chat for " + config.fullName(), e);
        }
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
