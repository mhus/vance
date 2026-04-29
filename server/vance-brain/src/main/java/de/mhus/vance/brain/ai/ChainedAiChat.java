package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@link AiChat} that fans out to a list of underlying AiChats — primary
 * first, fallbacks in order. The streaming side is composed via an outer
 * {@link ResilientStreamingChatModel} that advances to the next entry the
 * moment the previous one's retry budget is exhausted.
 *
 * <p>Each constituent AiChat already carries its own single-entry resilient
 * wrap (built by {@link AiModelService#createChat(AiChatConfig, AiChatOptions)}),
 * so the inner retries happen there. The outer policy here is "advance only" —
 * try once per entry, on any error move on, no further retries.
 *
 * <p>Sync side (sync {@code chatModel()} / {@link #ask(String)}) intentionally
 * uses only the primary entry. Engines drive their tool loops through
 * {@link #streamingChatModel()}, where the chain is in effect.
 */
class ChainedAiChat implements AiChat {

    /**
     * No retries — every transient failure of an entry has already been
     * handled by that entry's inner resilient layer; we just need to advance
     * on whatever bubbles up. Empty {@code retryOnPatterns} means
     * {@link RetryPolicy#shouldRetry(Throwable)} always returns false, so
     * every error path goes straight to "advance to next chain entry".
     */
    private static final RetryPolicy ADVANCE_ONLY = new RetryPolicy(
            1, Duration.ofMillis(1), Duration.ofMillis(1), List.of());

    private final String name;
    private final List<AiChat> entries;
    private final StreamingChatModel streaming;

    ChainedAiChat(String name, List<AiChat> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be empty");
        }
        this.name = name;
        this.entries = List.copyOf(entries);
        List<ChainEntry> outerChain = entries.stream()
                .map(c -> new ChainEntry(c.streamingChatModel(), c.getName(), ADVANCE_ONLY))
                .toList();
        this.streaming = new ResilientStreamingChatModel(outerChain);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ChatModel chatModel() {
        // Sync calls don't chain — only the primary handles them.
        return entries.get(0).chatModel();
    }

    @Override
    public StreamingChatModel streamingChatModel() {
        return streaming;
    }

    @Override
    public boolean isAvailable() {
        return entries.stream().allMatch(AiChat::isAvailable);
    }

    @Override
    public String ask(String question) {
        return entries.get(0).ask(question);
    }

    @Override
    public String askStream(String question, Consumer<String> tokenConsumer) {
        // Convenience wrapper — engines use streamingChatModel() directly.
        // Single-entry primary delegation keeps this simple.
        return entries.get(0).askStream(question, tokenConsumer);
    }
}
