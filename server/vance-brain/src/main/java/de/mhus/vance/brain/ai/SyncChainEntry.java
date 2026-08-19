package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.ChatModel;

/**
 * One entry in a {@link ResilientChatModel} chain — the sync twin of
 * {@link ChainEntry}.
 *
 * <p>Two records rather than one generic one: the delegate types are
 * unrelated interfaces ({@link ChatModel} vs
 * {@code StreamingChatModel}), and a type parameter would buy nothing
 * but a wildcard at every use site.
 *
 * @param delegate underlying model (typically already wrapped in
 *                 {@link LoggingChatModel} for tracing)
 * @param label    human-readable name for diagnostics, and the value
 *                 reported to {@code AiChatOptions.syncAnsweredBy} —
 *                 {@code "instance:modelName"}, the same form as
 *                 {@code AiChatConfig.fullName()}
 * @param policy   retry behaviour for this entry
 */
public record SyncChainEntry(
        ChatModel delegate,
        String label,
        RetryPolicy policy) {

    public SyncChainEntry {
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
