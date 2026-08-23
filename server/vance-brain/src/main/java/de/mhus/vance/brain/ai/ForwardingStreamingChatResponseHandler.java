package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/**
 * A {@link StreamingChatResponseHandler} that passes <b>every</b> callback on
 * to another one. The base for the decorator stack around a
 * {@code StreamingChatModel}.
 *
 * <p><b>Why this class earns its keep.</b> The interface has ten methods and
 * eight of them are {@code default} no-ops. An anonymous handler that overrides
 * the four everyone thinks of — text, thinking, complete, error — silently
 * swallows the rest: tool-call streaming
 * ({@link #onPartialToolCall}/{@link #onCompleteToolCall}), provider-specific
 * raw events ({@link #onUnmappedRawEvent}), and the {@code …Context}
 * overloads that carry the {@code StreamingHandle} a caller needs to cancel a
 * stream. Nothing fails; the events just stop at the decorator. Four decorators
 * in this package were written that way independently, so the bug was on its
 * way to being a house style — and it would only surface the day somebody wired
 * live tool-call progress into the UI, four layers away from the cause.
 *
 * <p>Subclasses override only what they actually observe and call
 * {@code super} — or, where the point is to transform, override deliberately.
 * The rule is that <em>not</em> mentioning a callback must mean "pass it
 * through", never "drop it".
 *
 * <p>Note the two-argument overloads are forwarded as two-argument calls rather
 * than falling back to the interface default that discards the context: that
 * default is the right compatibility behaviour for a leaf handler and the wrong
 * one for a decorator, which must not narrow what it relays.
 */
public class ForwardingStreamingChatResponseHandler implements StreamingChatResponseHandler {

    protected final StreamingChatResponseHandler delegate;

    public ForwardingStreamingChatResponseHandler(StreamingChatResponseHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        delegate.onPartialResponse(partialResponse);
    }

    @Override
    public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
        delegate.onPartialResponse(partialResponse, context);
    }

    @Override
    public void onPartialThinking(PartialThinking partialThinking) {
        delegate.onPartialThinking(partialThinking);
    }

    @Override
    public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
        delegate.onPartialThinking(partialThinking, context);
    }

    @Override
    public void onPartialToolCall(PartialToolCall partialToolCall) {
        delegate.onPartialToolCall(partialToolCall);
    }

    @Override
    public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
        delegate.onPartialToolCall(partialToolCall, context);
    }

    @Override
    public void onCompleteToolCall(CompleteToolCall completeToolCall) {
        delegate.onCompleteToolCall(completeToolCall);
    }

    @Override
    public void onUnmappedRawEvent(Object rawEvent) {
        delegate.onUnmappedRawEvent(rawEvent);
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        delegate.onCompleteResponse(completeResponse);
    }

    @Override
    public void onError(Throwable error) {
        delegate.onError(error);
    }
}
