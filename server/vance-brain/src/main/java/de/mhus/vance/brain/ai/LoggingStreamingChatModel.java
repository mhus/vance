package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorating {@link StreamingChatModel} that traces request and the
 * final aggregated response via {@link AiTraceLogger}, plus optionally
 * forwards the round-trip to a {@link LlmTraceWriter} for persistent
 * storage. Per-token partials are intentionally NOT logged at trace
 * level — they're reconstructible from the final
 * {@code aiMessage().text()} and would otherwise drown the trace log.
 */
public class LoggingStreamingChatModel implements StreamingChatModel {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingStreamingChatModel.class);

    private final String name;
    private final StreamingChatModel delegate;
    private final @Nullable LlmTraceWriter traceWriter;

    public LoggingStreamingChatModel(String name, StreamingChatModel delegate) {
        this(name, delegate, null);
    }

    public LoggingStreamingChatModel(
            String name, StreamingChatModel delegate, @Nullable LlmTraceWriter traceWriter) {
        this.name = name;
        this.delegate = delegate;
        this.traceWriter = traceWriter;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        AiTraceLogger.logRequest(name, request);
        long started = System.currentTimeMillis();
        delegate.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                handler.onPartialResponse(partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse complete) {
                AiTraceLogger.logResponse(name, complete);
                safeRecord(request, complete, System.currentTimeMillis() - started);
                handler.onCompleteResponse(complete);
            }

            @Override
            public void onError(Throwable error) {
                AiTraceLogger.logStreamingError(name, error);
                safeRecord(request, null, System.currentTimeMillis() - started);
                handler.onError(error);
            }
        });
    }

    private void safeRecord(ChatRequest request, @Nullable ChatResponse response, long elapsedMs) {
        if (traceWriter == null) return;
        try {
            traceWriter.onRoundtrip(request, response, elapsedMs);
        } catch (RuntimeException e) {
            LOG.warn("LlmTraceWriter threw — ignoring (chat='{}'): {}", name, e.toString());
        }
    }
}
