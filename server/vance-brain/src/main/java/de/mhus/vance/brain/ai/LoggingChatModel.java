package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorating {@link ChatModel} that traces every request/response via
 * {@link AiTraceLogger} and — when one is supplied — also forwards the
 * round-trip to a {@link LlmTraceWriter} for persistent storage.
 * Default-method overloads ({@code chat(String)}, {@code chat(ChatMessage...)})
 * delegate to {@link #chat(ChatRequest)}, so wrapping that single
 * method covers all entry points.
 */
public class LoggingChatModel implements ChatModel {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingChatModel.class);

    private final String name;
    private final ChatModel delegate;
    private final @Nullable LlmTraceWriter traceWriter;

    public LoggingChatModel(String name, ChatModel delegate) {
        this(name, delegate, null);
    }

    public LoggingChatModel(String name, ChatModel delegate, @Nullable LlmTraceWriter traceWriter) {
        this.name = name;
        this.delegate = delegate;
        this.traceWriter = traceWriter;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        AiTraceLogger.logRequest(name, request);
        long started = System.currentTimeMillis();
        ChatResponse response;
        try {
            response = delegate.chat(request);
        } catch (RuntimeException e) {
            AiTraceLogger.logStreamingError(name, e);
            // Persist a row even on failure so the trace log shows the
            // attempt — caller-supplied writer may want it.
            safeRecord(request, null, System.currentTimeMillis() - started);
            throw e;
        }
        AiTraceLogger.logResponse(name, response);
        safeRecord(request, response, System.currentTimeMillis() - started);
        return response;
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
