package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/**
 * Decorating {@link StreamingChatModel} that traces request and the
 * final aggregated response. Per-token partials are intentionally
 * NOT logged at trace level — they're reconstructible from the
 * final {@code aiMessage().text()} and would otherwise drown the
 * trace log.
 */
public class LoggingStreamingChatModel implements StreamingChatModel {

    private final String name;
    private final StreamingChatModel delegate;

    public LoggingStreamingChatModel(String name, StreamingChatModel delegate) {
        this.name = name;
        this.delegate = delegate;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        AiTraceLogger.logRequest(name, request);
        delegate.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                handler.onPartialResponse(partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse complete) {
                AiTraceLogger.logResponse(name, complete);
                handler.onCompleteResponse(complete);
            }

            @Override
            public void onError(Throwable error) {
                AiTraceLogger.logStreamingError(name, error);
                handler.onError(error);
            }
        });
    }
}
