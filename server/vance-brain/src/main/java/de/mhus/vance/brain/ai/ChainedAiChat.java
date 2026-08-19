package de.mhus.vance.brain.ai;

import de.mhus.vance.brain.ai.attachment.ResolvedAttachment;
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
 * <p>Both sides chain. That was not always true: the sync side used to
 * hand out the primary entry alone, on the reasoning that engines drive
 * their tool loops through {@link #streamingChatModel()}. But plenty does
 * not stream — Jeltz, Marvin, the Slartibartfast phases, memory
 * compaction, Eddie's triage, {@code LightLlmService} — and all of it was
 * silently running without the fallback the tenant had configured.
 */
class ChainedAiChat implements AiChat {

    /**
     * No retries — every transient failure of an entry has already been
     * handled by that entry's inner resilient layer; we just need to advance
     * on whatever bubbles up. {@code maxAttempts = 1} is what guarantees
     * that: with no attempt left, both the retriable and the non-retriable
     * branch in {@link ResilientStreamingChatModel} advance to the next
     * chain entry. (Empty {@code retryOnPatterns} no longer implies
     * "never" on its own — {@link RetryPolicy#shouldRetry(Throwable)} also
     * honours langchain4j's typed retriable marker.)
     */
    private static final RetryPolicy ADVANCE_ONLY = new RetryPolicy(
            1, Duration.ofMillis(1), Duration.ofMillis(1), List.of());

    private final String name;
    private final List<AiChat> entries;
    private final StreamingChatModel streaming;
    private final ChatModel sync;

    ChainedAiChat(String name, List<AiChat> entries) {
        this(name, entries, AiChatOptions.builder().build());
    }

    ChainedAiChat(String name, List<AiChat> entries, AiChatOptions options) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be empty");
        }
        this.name = name;
        this.entries = List.copyOf(entries);
        List<ChainEntry> outerChain = entries.stream()
                .map(c -> new ChainEntry(c.streamingChatModel(), c.getName(), ADVANCE_ONLY))
                .toList();
        this.streaming = new ResilientStreamingChatModel(outerChain);
        List<SyncChainEntry> outerSyncChain = entries.stream()
                .map(c -> new SyncChainEntry(c.chatModel(), c.getName(), ADVANCE_ONLY))
                .toList();
        this.sync = new ResilientChatModel(
                outerSyncChain,
                options.getUserNotifier(),
                options.getToolLimitLearner(),
                options.getSyncCallDeadline(),
                options.getSyncAnsweredBy());
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ProviderType providerType() {
        return entries.get(0).providerType();
    }

    @Override
    public ChatModel chatModel() {
        // Advance-only across entries; each entry's own retries already
        // happened inside its StandardAiChat wrapper, same as streaming.
        return sync;
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
    public String ask(String question, List<ResolvedAttachment> attachments) {
        return entries.get(0).ask(question, attachments);
    }

    @Override
    public String askStream(
            String question,
            Consumer<String> tokenConsumer,
            List<ResolvedAttachment> attachments) {
        // Convenience wrapper — engines use streamingChatModel() directly.
        // Single-entry primary delegation keeps this simple.
        return entries.get(0).askStream(question, tokenConsumer, attachments);
    }
}
