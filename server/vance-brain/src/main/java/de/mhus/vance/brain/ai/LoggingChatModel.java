package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Decorating {@link ChatModel} that traces every request/response
 * via {@link AiTraceLogger}. Default-method overloads
 * ({@code chat(String)}, {@code chat(ChatMessage...)}) on the
 * {@link ChatModel} interface delegate to {@link #chat(ChatRequest)},
 * so wrapping that single method covers all entry points.
 */
public class LoggingChatModel implements ChatModel {

    private final String name;
    private final ChatModel delegate;

    public LoggingChatModel(String name, ChatModel delegate) {
        this.name = name;
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        AiTraceLogger.logRequest(name, request);
        ChatResponse response;
        try {
            response = delegate.chat(request);
        } catch (RuntimeException e) {
            AiTraceLogger.logStreamingError(name, e);
            throw e;
        }
        AiTraceLogger.logResponse(name, response);
        return response;
    }
}
