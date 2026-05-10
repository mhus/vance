package de.mhus.vance.brain.ai;

import de.mhus.vance.brain.ai.attachment.ResolvedAttachment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;
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
     * Typed identity of the backend driving this chat. For chained chats
     * (multi-entry behavior), this is the primary entry's provider — the
     * one the chain falls back from.
     */
    ProviderType providerType();

    /**
     * Synchronous one-shot: send {@code question}, receive the full text.
     *
     * @throws AiChatException on any provider or transport failure
     */
    default String ask(String question) {
        return ask(question, List.of());
    }

    /**
     * Synchronous one-shot with multimodal attachments. The attachments
     * are inserted as content blocks (image / PDF / text) on the user
     * message, ahead of the question text — they're treated as static
     * context, the question is the dynamic instruction.
     *
     * <p>Providers / models without native PDF support apply a PDFBox
     * text-extract fallback transparently; image-without-vision-capability
     * models reject the call up-front rather than wasting a round trip.
     *
     * @throws AiChatException on any provider or transport failure
     */
    String ask(String question, List<ResolvedAttachment> attachments);

    /**
     * Streaming one-shot: partial tokens are handed to {@code tokenConsumer}
     * as they arrive; the returned String is the full concatenated text once
     * the stream completes. Blocks the caller until completion.
     *
     * @throws AiChatException on any provider or transport failure
     */
    default String askStream(String question, Consumer<String> tokenConsumer) {
        return askStream(question, tokenConsumer, List.of());
    }

    /**
     * Streaming variant of {@link #ask(String, List)}.
     *
     * @throws AiChatException on any provider or transport failure
     */
    String askStream(
            String question,
            Consumer<String> tokenConsumer,
            List<ResolvedAttachment> attachments);

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
