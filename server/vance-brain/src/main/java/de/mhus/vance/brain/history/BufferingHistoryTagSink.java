package de.mhus.vance.brain.history;

import de.mhus.vance.shared.chat.ChatMessageService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

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

    /**
     * How many failed calls of a turn are carried into the persisted
     * history. A turn that fails more often than this has a systematic
     * problem, and the first few entries describe it just as well as
     * twenty would — while the block still has to survive being replayed
     * in every later turn.
     */
    static final int MAX_FAILURES = 5;

    /** Per-entry clip. Long enough for a path plus a reason. */
    static final int MAX_FAILURE_CHARS = 300;

    private final Set<String> buffer = new LinkedHashSet<>();
    private final List<String> failures = new ArrayList<>();

    @Override
    public synchronized void emit(Set<String> tags) {
        if (tags == null || tags.isEmpty()) return;
        buffer.addAll(tags);
    }

    @Override
    public synchronized void emitFailure(String toolName, @Nullable String message) {
        if (toolName == null || toolName.isBlank()) return;
        if (failures.size() >= MAX_FAILURES) return;
        String reason = message == null || message.isBlank() ? "no reason reported" : message;
        if (reason.length() > MAX_FAILURE_CHARS) {
            reason = reason.substring(0, MAX_FAILURE_CHARS) + "…";
        }
        String entry = toolName + " → " + reason;
        // Same tool failing the same way twice in a turn is one fact.
        if (!failures.contains(entry)) failures.add(entry);
    }

    /**
     * Persist all buffered tags onto {@code messageId} via
     * {@link ChatMessageService#tag}, plus the buffered tool failures via
     * {@link ChatMessageService#recordToolFailures}, and clear both
     * buffers. Idempotent: a second call after the first flush is a
     * no-op (buffers empty).
     */
    public synchronized void flushTo(String messageId, ChatMessageService service) {
        if (messageId == null || messageId.isBlank()) return;
        if (buffer.isEmpty() && failures.isEmpty()) return;
        Set<String> tagSnapshot = new LinkedHashSet<>(buffer);
        List<String> failureSnapshot = List.copyOf(failures);
        buffer.clear();
        failures.clear();
        try {
            service.tag(messageId, tagSnapshot);
            service.recordToolFailures(messageId, failureSnapshot);
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

    /** Snapshot of currently buffered tool failures — for tests / introspection. */
    public synchronized List<String> peekFailures() {
        return List.copyOf(failures);
    }

    /** Drop both buffers without writing anywhere — for abort paths. */
    public synchronized void discard() {
        buffer.clear();
        failures.clear();
    }
}
