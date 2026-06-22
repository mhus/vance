package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.metric.MetricService;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.jspecify.annotations.Nullable;

/**
 * Streaming {@link StreamingChatModel} decorator that normalises
 * {@code ToolExecutionRequest.arguments} in the final aggregated
 * response before passing it up. Hack-fix for the DeepSeek-V4-Pro
 * {@code function.arguments} quirk — see {@link ToolArgumentNormalizer}
 * for the full story.
 *
 * <p>Partial tokens pass through untouched; tool-call args are only
 * exposed in {@link StreamingChatResponseHandler#onCompleteResponse}.
 * Wrapped unconditionally outside {@link ResilientStreamingChatModel}
 * so the trace/log layers see the raw provider output (forensic value)
 * while engines + chat-history persistence see the cleaned form.
 */
public class ToolArgumentNormalizingStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;
    private final String modelName;
    private final @Nullable MetricService metrics;

    public ToolArgumentNormalizingStreamingChatModel(
            StreamingChatModel delegate, String modelName, @Nullable MetricService metrics) {
        this.delegate = delegate;
        this.modelName = modelName;
        this.metrics = metrics;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        delegate.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                handler.onPartialResponse(partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse complete) {
                handler.onCompleteResponse(
                        ToolArgumentNormalizer.normalize(complete, modelName, metrics));
            }

            @Override
            public void onError(Throwable error) {
                handler.onError(error);
            }
        });
    }
}
