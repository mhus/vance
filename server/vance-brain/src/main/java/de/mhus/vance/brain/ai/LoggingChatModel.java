package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.metric.MetricService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorating {@link ChatModel} that traces every request/response via
 * {@link AiTraceLogger}, emits a compact per-call stats line plus
 * char-length metrics via {@link LlmCallStatsLogger}, and — when one is
 * supplied — also forwards the round-trip to a {@link LlmTraceWriter}
 * for persistent storage. Default-method overloads
 * ({@code chat(String)}, {@code chat(ChatMessage...)}) delegate to
 * {@link #chat(ChatRequest)}, so wrapping that single method covers
 * all entry points.
 */
public class LoggingChatModel implements ChatModel {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingChatModel.class);

    private final String name;
    private final ChatModel delegate;
    private final @Nullable LlmTraceWriter traceWriter;
    private final @Nullable MetricService metricService;

    public LoggingChatModel(String name, ChatModel delegate) {
        this(name, delegate, null, null);
    }

    public LoggingChatModel(String name, ChatModel delegate, @Nullable LlmTraceWriter traceWriter) {
        this(name, delegate, traceWriter, null);
    }

    public LoggingChatModel(
            String name,
            ChatModel delegate,
            @Nullable LlmTraceWriter traceWriter,
            @Nullable MetricService metricService) {
        this.name = name;
        this.delegate = delegate;
        this.traceWriter = traceWriter;
        this.metricService = metricService;
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
            long elapsed = System.currentTimeMillis() - started;
            LlmCallStatsLogger.record(name, request, null, elapsed, metricService);
            // Persist a row even on failure so the trace log shows the
            // attempt — caller-supplied writer may want it.
            safeRecord(request, null, elapsed);
            throw e;
        }
        AiTraceLogger.logResponse(name, response);
        long elapsed = System.currentTimeMillis() - started;
        LlmCallStatsLogger.record(name, request, response, elapsed, metricService);
        safeRecord(request, response, elapsed);
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
