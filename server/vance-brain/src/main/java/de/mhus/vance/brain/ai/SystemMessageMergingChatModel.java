package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Request-side decorator that merges consecutive system messages before
 * they reach the provider — see {@link SystemMessageMerger} for why.
 *
 * <p>Unlike {@link SanitizingChatModel}, which cleans the response, this
 * one rewrites the request and leaves the answer untouched. It is
 * inserted only for models whose catalog entry sets
 * {@code mergeSystemMessages: true}.
 */
public class SystemMessageMergingChatModel implements ChatModel {

    private final ChatModel delegate;

    public SystemMessageMergingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return delegate.chat(SystemMessageMerger.merge(request));
    }
}
