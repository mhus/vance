package de.mhus.vance.brain.ai.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.CacheTtl;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Synchronous langchain4j {@link ChatModel} implementation that talks
 * directly to the Anthropic Messages API via the official
 * {@code anthropic-java} SDK.
 *
 * <p>Why a direct adapter instead of {@code langchain4j-anthropic}: the
 * latter doesn't expose {@code cache_control} markers, which is the
 * point of this layer. We get caching, future beta-headers
 * (extended-thinking, structured outputs) and request-level access to
 * raw JSON via {@code putAdditionalBodyProperty}, while the rest of
 * Vance keeps using langchain4j's generic {@link ChatModel} abstraction.
 *
 * <p>The {@link AiChatOptions} are immutable per-call config (model,
 * max tokens, temperature, cache boundary, TTL). One adapter instance
 * binds one config; engines call {@link #chat(ChatRequest)} with as
 * many requests as they like.
 */
@RequiredArgsConstructor
@Slf4j
public class AnthropicDirectChatModel implements ChatModel {

    private final AnthropicClient client;
    private final String modelName;
    private final int maxTokens;
    private final AiChatOptions options;

    @Override
    public ChatResponse chat(ChatRequest request) {
        MessageCreateParams params = buildParams(request);
        Message response = client.messages().create(params);
        return AnthropicResponseMapper.toChatResponse(response);
    }

    /**
     * Build the {@link MessageCreateParams} for {@code request}, applying
     * the cache-boundary marker and the 1h-TTL beta header where
     * configured. Package-private so the streaming adapter can reuse it.
     */
    MessageCreateParams buildParams(ChatRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(modelName)
                .maxTokens(maxTokens);
        if (options.getCacheTtl() == CacheTtl.LONG_1H) {
            builder.putAdditionalHeader(
                    "anthropic-beta", "extended-cache-ttl-2025-04-11");
        }
        AnthropicRequestMapper.apply(builder, request, options);
        return builder.build();
    }
}
