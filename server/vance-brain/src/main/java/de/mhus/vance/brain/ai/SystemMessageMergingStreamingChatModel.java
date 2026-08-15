package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/**
 * Streaming counterpart of {@link SystemMessageMergingChatModel}.
 *
 * <p>Chat turns run through the streaming model, so a merge that only
 * covered the sync path would leave the actual production traffic
 * untouched — which is exactly the traffic that hit the context
 * overflow.
 */
public class SystemMessageMergingStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;

    public SystemMessageMergingStreamingChatModel(StreamingChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        delegate.chat(SystemMessageMerger.merge(request), handler);
    }
}
