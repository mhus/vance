package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.jspecify.annotations.Nullable;

/**
 * Normalizes the token usage on every response so the prompt-cache counters
 * survive into the layers above — the usage ledger, the trace log, the call
 * stats and the progress feed all read the response, and none of them should
 * need to know which langchain4j usage class a provider happens to use.
 *
 * <p>Innermost below accounting by design (wired in
 * {@code AbstractChatProvider#createChat}): the ledger is the one consumer
 * that <i>bills</i> from these numbers, so it must see the adjusted
 * uncached-input count, not the raw provider total. See
 * {@link CacheAwareTokenUsageAdapter} for the counting semantics.
 *
 * <p>Also attaches the implicit-cache estimate for gateways that bill a tail
 * without itemizing ({@link ImplicitCacheEstimator}) — computed here, once per
 * attempt, and carried on the usage so no upper layer recomputes it.
 *
 * <p>Passes the response through untouched when there is nothing to map —
 * including the Anthropic wire, whose usage class already speaks
 * {@link CacheAwareTokenUsage}.
 *
 * @see CacheAwareUsageStreamingChatModel
 */
public class CacheAwareUsageChatModel implements ChatModel {

    private final ChatModel delegate;

    public CacheAwareUsageChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return withNormalizedUsage(request, delegate.chat(request));
    }

    /**
     * Rebuilds the response around the normalized usage and, where the
     * provider itemized nothing, the attached estimate. Shared with the
     * streaming twin, whose final complete response is the same object shape
     * with the same metadata rules.
     *
     * <p>The rebuild goes through {@code metadata}: a response always has
     * one, and the metadata builder keeps id, model name and finish reason
     * intact while the discrete setters would not.
     *
     * <p><b>The metadata is rebuilt as the generic class, never through the
     * provider's own {@code toBuilder()}.</b> Provider metadata types narrow
     * the usage to their own class ({@code OpenAiChatResponseMetadata} holds an
     * {@code OpenAiTokenUsage}), and their builders silently coerce any other
     * {@link TokenUsage} into it by copying the two counts. That is exactly
     * what destroys the normalization: the cache counters and the
     * {@link CacheAwareTokenUsage} marker vanish, the ledger books a cached
     * call as {@code cacheRead = 0} and the estimator then invents an
     * "implicit" cache on top. Nothing downstream reads provider specific
     * metadata, so the generic class loses nothing that is used.
     */
    static @Nullable ChatResponse withNormalizedUsage(@Nullable ChatRequest request, @Nullable ChatResponse response) {
        if (response == null) return null;
        TokenUsage raw = response.tokenUsage();
        TokenUsage mapped = CacheAwareTokenUsageAdapter.map(raw);
        TokenUsage withEstimate =
                ImplicitCacheTokenUsage.wrap(mapped, ImplicitCacheEstimator.estimate(request, mapped));
        if (withEstimate == raw) return response;
        ChatResponseMetadata original = response.metadata();
        ChatResponseMetadata.Builder<?> metadata =
                ChatResponseMetadata.builder().tokenUsage(withEstimate);
        if (original != null) {
            metadata.id(original.id()).modelName(original.modelName()).finishReason(original.finishReason());
        }
        return response.toBuilder().metadata(metadata.build()).build();
    }
}
