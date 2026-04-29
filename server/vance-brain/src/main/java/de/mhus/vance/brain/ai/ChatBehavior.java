package de.mhus.vance.brain.ai;

import java.util.List;

/**
 * Specification for how a {@link AiChat} should react to provider errors —
 * which model to try first, which fallbacks to fall through to, and per-entry
 * retry policy. Built at the engine call-site (typically by
 * {@code ChatBehaviorBuilder} from {@code engineParams}); consumed by
 * {@link AiModelService#createChat(ChatBehavior, AiChatOptions)}.
 *
 * <p>Single-entry behaviour is equivalent to the pre-Phase-B path
 * ({@link AiModelService#createChat(AiChatConfig, AiChatOptions)}); this
 * record exists so callers that need a chain can describe it without
 * coupling to the chat-construction details.
 *
 * @param entries primary first; fallbacks in order. Must be non-empty.
 */
public record ChatBehavior(List<Entry> entries) {

    public ChatBehavior {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("ChatBehavior.entries must not be empty");
        }
        entries = List.copyOf(entries);
    }

    /** Convenience: build a single-entry behaviour. */
    public static ChatBehavior single(AiChatConfig config) {
        return new ChatBehavior(List.of(new Entry(config, "primary")));
    }

    /**
     * One link in the fallback chain.
     *
     * <p>Each entry is built into a regular single-entry-{@link AiChat} via
     * {@link AiModelService#createChat(AiChatConfig, AiChatOptions)}, which
     * wraps the call in a {@link ResilientStreamingChatModel} with
     * {@link RetryPolicy#DEFAULT}. That handles per-entry retries; the outer
     * chain advances to the next entry only after this entry's retry budget
     * is exhausted.
     *
     * <p>(Per-entry custom retry policies aren't supported yet — when a
     * concrete need arises, add a {@code retryPolicy} field here and wire it
     * through {@code AiModelService}.)
     *
     * @param config provider + model + apiKey for this entry
     * @param label  human-readable name for diagnostics (e.g.
     *               {@code "primary"}, {@code "fallback:default-fast"})
     */
    public record Entry(AiChatConfig config, String label) {
        public Entry {
            if (config == null) {
                throw new IllegalArgumentException("config is null");
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("label is blank");
            }
        }
    }
}
