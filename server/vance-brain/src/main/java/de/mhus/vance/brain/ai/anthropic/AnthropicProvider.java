package de.mhus.vance.brain.ai.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelProvider;
import de.mhus.vance.brain.ai.CacheBoundary;
import de.mhus.vance.brain.ai.StandardAiChat;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Anthropic backend for {@link AiModelProvider}, built on top of
 * {@link AnthropicDirectChatModel} so prompt caching and beta headers
 * are reachable. Replaces the langchain4j-anthropic implementation
 * (the latter doesn't expose {@code cache_control}).
 *
 * <p>Per-call sync + streaming pair share the same {@link AnthropicClient}
 * — the SDK is thread-safe and reusing the client keeps the connection
 * pool warm for the duration of one chat.
 *
 * <p>Honors the global {@code vance.ai.cache.enabled} switch
 * (default {@code true}). When set to {@code false}, the provider
 * downgrades any inbound {@link AiChatOptions#getCacheBoundary()} to
 * {@link CacheBoundary#NONE} before constructing the chat — engines
 * keep using {@code AiChatOptions} as usual, the operator-level kill
 * switch wins.
 */
@Component
@Slf4j
public class AnthropicProvider implements AiModelProvider {

    public static final String NAME = "anthropic";

    /** Anthropic's API requires maxTokens; pick a safe upper bound when callers omit it. */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final boolean cacheEnabled;

    public AnthropicProvider(
            @Value("${vance.ai.cache.enabled:true}") boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
        if (!cacheEnabled) {
            log.info("Anthropic prompt caching DISABLED via vance.ai.cache.enabled=false");
        }
    }

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
        AiChatOptions effective = applyGlobalCacheKill(options);
        int maxTokens = effective.getMaxTokens() != null
                ? effective.getMaxTokens()
                : DEFAULT_MAX_TOKENS;
        Duration timeout = Duration.ofSeconds(effective.getTimeoutSeconds());
        try {
            AnthropicClient client = AnthropicOkHttpClient.builder()
                    .apiKey(config.apiKey())
                    .timeout(timeout)
                    .build();
            ChatModel sync = new AnthropicDirectChatModel(
                    client, config.modelName(), maxTokens, effective);
            StreamingChatModel streaming = new AnthropicDirectStreamingChatModel(
                    client, config.modelName(), maxTokens, effective);
            log.debug("Built Anthropic chat: model='{}', maxTokens={}, "
                            + "cacheBoundary={}, ttl={}",
                    config.modelName(), maxTokens,
                    effective.getCacheBoundary(), effective.getCacheTtl());
            return new StandardAiChat(config.fullName(), sync, streaming, effective);
        } catch (RuntimeException e) {
            throw new AiChatException(
                    "Failed to build Anthropic chat for " + config.fullName(), e);
        }
    }

    /**
     * Force {@link CacheBoundary#NONE} when the global switch is off.
     * Keeps the AiChatOptions immutable contract intact by returning a
     * fresh builder copy rather than mutating the input.
     */
    private AiChatOptions applyGlobalCacheKill(AiChatOptions options) {
        if (cacheEnabled) {
            return options;
        }
        if (options.getCacheBoundary() == CacheBoundary.NONE) {
            return options;
        }
        return options.toBuilder().cacheBoundary(CacheBoundary.NONE).build();
    }
}
