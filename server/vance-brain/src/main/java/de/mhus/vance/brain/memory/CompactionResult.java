package de.mhus.vance.brain.memory;

import org.jspecify.annotations.Nullable;

/**
 * Outcome of a {@link MemoryCompactionService#compact} call.
 *
 * <p>{@code compacted=false} signals a no-op (history too short, LLM
 * call failed, etc.). {@code reason} is human-readable in either case
 * — useful for logs and the {@code process-compact} reply.
 */
public record CompactionResult(
        boolean compacted,
        int messagesCompacted,
        int summaryChars,
        @Nullable String memoryId,
        @Nullable String supersededMemoryId,
        @Nullable String reason) {

    public static CompactionResult noop(String reason) {
        return new CompactionResult(false, 0, 0, null, null, reason);
    }

    public static CompactionResult success(
            int messagesCompacted,
            int summaryChars,
            String memoryId,
            @Nullable String supersededMemoryId) {
        return new CompactionResult(true, messagesCompacted, summaryChars,
                memoryId, supersededMemoryId, null);
    }
}
