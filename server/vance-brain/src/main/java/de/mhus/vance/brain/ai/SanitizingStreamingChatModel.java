package de.mhus.vance.brain.ai;

import de.mhus.vance.brain.ai.parser.MessageParser;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.jspecify.annotations.Nullable;

/**
 * Streaming counterpart of {@link SanitizingChatModel} — applies the
 * resolved {@link MessageParser} to the aggregated
 * {@link ChatResponse} on {@code onCompleteResponse}. Partial tokens
 * pass through untouched; tool-call args and synthesized tool calls
 * only become visible to the engine layer at completion.
 *
 * <p>Wrapped OUTSIDE {@link ResilientStreamingChatModel} so the trace
 * + retry layers see the raw provider output while engines + chat-
 * history persistence see the parsed form. When the active model has
 * no {@link MessageParser} bound, callers skip the wrap entirely (the
 * decorator is otherwise a no-op).
 */
public class SanitizingStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;
    private final MessageParser parser;

    public SanitizingStreamingChatModel(StreamingChatModel delegate, MessageParser parser) {
        this.delegate = delegate;
        this.parser = parser;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        // Forwarding base, so the callbacks this decorator has no opinion
        // about (tool-call streaming, raw provider events, the cancel handle)
        // reach the caller instead of stopping here.
        delegate.chat(request, new ForwardingStreamingChatResponseHandler(handler) {
            @Override
            public void onCompleteResponse(ChatResponse complete) {
                // The only thing this decorator changes. Reasoning and text
                // deltas pass through untouched — the parser rewrites the
                // aggregated final response, not the stream.
                ChatResponse rewritten = complete == null ? null : parser.parse(complete);
                super.onCompleteResponse(rewritten == null ? complete : rewritten);
            }
        });
    }

    /**
     * Builder helper for the call site in {@link StandardAiChat}:
     * returns the decorated streaming model only when a parser is
     * configured; otherwise the {@code delegate} is returned
     * unchanged so the call-graph stays flat for the 95 %-case.
     */
    public static StreamingChatModel wrapIfNeeded(
            StreamingChatModel delegate, @Nullable MessageParser parser) {
        if (delegate == null || parser == null) return delegate;
        return new SanitizingStreamingChatModel(delegate, parser);
    }
}
