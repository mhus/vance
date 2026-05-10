package de.mhus.vance.brain.history;

import de.mhus.vance.shared.chat.ChatMessageService;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory accumulator for history marker tags during a single engine
 * turn. Tags emitted by tools and engine hooks are buffered until the
 * engine has persisted the assistant {@code ChatMessageDocument} of the
 * turn — at which point the buffer is {@link #flushTo flushed} to that
 * message via {@link ChatMessageService#tag}.
 *
 * <p>Required because tool calls run before the assistant message
 * exists: the LLM emits tool_use blocks during the round-trip, the
 * engine appends the final assistant text only after the loop ends.
 *
 * <p>Thread-safe: tools may run on the engine thread but plan-mode
 * hooks fire from the same call chain — synchronisation here keeps
 * concurrent emit + flush from racing.
 */
@Slf4j
public final class BufferingHistoryTagSink implements HistoryTagSink {

    private final Set<String> buffer = new LinkedHashSet<>();

    @Override
    public synchronized void emit(Set<String> tags) {
        if (tags == null || tags.isEmpty()) return;
        buffer.addAll(tags);
    }

    /**
     * Persist all buffered tags onto {@code messageId} via
     * {@link ChatMessageService#tag} and clear the buffer. Idempotent:
     * a second call after the first flush is a no-op (buffer empty).
     */
    public synchronized void flushTo(String messageId, ChatMessageService service) {
        if (buffer.isEmpty() || messageId == null || messageId.isBlank()) {
            return;
        }
        Set<String> snapshot = new LinkedHashSet<>(buffer);
        buffer.clear();
        try {
            service.tag(messageId, snapshot);
        } catch (RuntimeException e) {
            // Tag write failure must not cascade to the engine's
            // user-visible result. Log and move on — the assistant
            // message is already persisted; only the markers are missed.
            log.warn("History-tag flush failed for message {}: {}", messageId, e.toString());
        }
    }

    /** Snapshot of currently buffered tags — for tests / introspection. */
    public synchronized Set<String> peek() {
        return Set.copyOf(buffer);
    }

    /** Drop the buffer without writing anywhere — for abort paths. */
    public synchronized void discard() {
        buffer.clear();
    }
}
