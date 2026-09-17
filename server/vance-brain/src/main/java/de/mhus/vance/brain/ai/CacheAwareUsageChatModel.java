package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
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
        return withNormalizedUsage(delegate.chat(request));
    }

    /**
     * Rebuilds the response around the normalized usage. Shared with the
     * streaming twin, whose final complete response is the same object
     * shape with the same metadata rules.
     *
     * <p>The rebuild goes through {@code metadata}: a response always has
     * one, and the metadata builder keeps id, model name and finish reason
     * intact while the discrete setters would not.
     */
    static @Nullable ChatResponse withNormalizedUsage(@Nullable ChatResponse response) {
        if (response == null) return null;
        TokenUsage raw = response.tokenUsage();
        TokenUsage mapped = CacheAwareTokenUsageAdapter.map(raw);
        if (mapped == raw) return response;
        return response.toBuilder()
                .metadata(response.metadata().toBuilder().tokenUsage(mapped).build())
                .build();
    }
}
