package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/**
 * Streaming twin of {@link CacheAwareUsageChatModel}. Token usage arrives on
 * exactly one frame — OpenAI sends the usage object once, in the final
 * complete response — so normalizing that frame covers the whole call.
 *
 * <p>Everything else forwards unchanged via
 * {@link ForwardingStreamingChatResponseHandler}: a decorator that mentions
 * only {@code onCompleteResponse} must not narrow the stream it sits in.
 */
public class CacheAwareUsageStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;

    public CacheAwareUsageStreamingChatModel(StreamingChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        delegate.chat(request, new ForwardingStreamingChatResponseHandler(handler) {
            @Override
            public void onCompleteResponse(ChatResponse complete) {
                super.onCompleteResponse(CacheAwareUsageChatModel.withNormalizedUsage(complete));
            }
        });
    }
}
