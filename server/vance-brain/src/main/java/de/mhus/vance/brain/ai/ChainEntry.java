package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * One entry in a {@link ResilientStreamingChatModel} chain — pairs a
 * concrete provider model with the retry policy that governs it.
 *
 * <p>Phase A always builds a single-element chain (primary only). Phase B
 * will add fallback entries (e.g. {@code primary → cheaper-tier → other-provider})
 * without changing the decorator code.
 *
 * @param delegate underlying model (typically already wrapped in
 *                 {@code LoggingStreamingChatModel} for tracing)
 * @param label    human-readable name for diagnostics (e.g.
 *                 {@code "gemini:gemini-2.5-pro"})
 * @param policy   retry behaviour for this entry
 */
public record ChainEntry(
        StreamingChatModel delegate,
        String label,
        RetryPolicy policy) {

    public ChainEntry {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate is null");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label is blank");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy is null");
        }
    }
}
