package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.function.Consumer;

/**
 * A configured chat instance bound to a specific provider + model. Short
 * lived — create one per logical conversation turn.
 *
 * <p><b>What is abstracted:</b> the provider-specific wiring (which builder,
 * how to plumb the API key, how to translate our {@link AiChatOptions} into
 * provider-specific parameters, any rate-limiting or caching decisions).
 *
 * <p><b>What is <i>not</i> abstracted:</b> langchain4j itself. Callers who
 * need finer control than {@link #ask(String)} /
 * {@link #askStream(String, Consumer)} can grab the underlying
 * {@link ChatModel} or {@link StreamingChatModel} via {@link #chatModel()} /
 * {@link #streamingChatModel()} and pass them straight into langchain4j
 * request builders, AI services, or langgraph4j state-graph nodes. Adding a
 * custom wrapper layer for every such feature would be churn — the moment
 * we need a capability langchain4j already offers, we just reach for it.
 */
public interface AiChat {

    /** {@code "provider:model"} form, for logs and diagnostics. */
    String getName();

    /**
     * Synchronous one-shot: send {@code question}, receive the full text.
     *
     * @throws AiChatException on any provider or transport failure
     */
    String ask(String question);

    /**
     * Streaming one-shot: partial tokens are handed to {@code tokenConsumer}
     * as they arrive; the returned String is the full concatenated text once
     * the stream completes. Blocks the caller until completion.
     *
     * @throws AiChatException on any provider or transport failure
     */
    String askStream(String question, Consumer<String> tokenConsumer);

    /**
     * The underlying langchain4j sync model. Use when the caller needs
     * request features this interface doesn't expose directly — tool specs,
     * structured output, memory-backed AiServices, graph nodes, etc.
     */
    ChatModel chatModel();

    /**
     * The underlying langchain4j streaming model. Same escape-hatch
     * rationale as {@link #chatModel()}.
     */
    StreamingChatModel streamingChatModel();

    /** Whether this instance is still fit to serve calls. */
    boolean isAvailable();
}
