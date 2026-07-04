package de.mhus.vance.brain.history;

import org.jspecify.annotations.Nullable;

/**
 * In-memory accumulator for the model's raw streamed narration
 * ("thoughts") during a single engine turn. The structured-action loop
 * appends each iteration's raw response text verbatim — exactly what the
 * user saw stream live, including any {@code <think>…</think>} monologue.
 * The engine reads {@link #snapshot()} when it persists the assistant
 * {@code ChatMessageDocument} of the turn and stores it in the
 * {@code thinking} field, so the raw thoughts stay reviewable after the
 * live stream is replaced by the final answer.
 *
 * <p>Lifecycle mirrors {@link BufferingHistoryTagSink}: a fresh buffer
 * is created per lifecycle call in {@code ThinkEngineService}, so it
 * only lives for the duration of one turn's context. Accumulating
 * across iterations (and across a judge-driven loop re-run in Eddie) is
 * intentional — the full turn's reasoning is captured, not just the
 * last iteration's.
 *
 * <p>Thread-safe: tools and hooks run on the engine thread, but the
 * synchronisation keeps a concurrent append + snapshot from racing.
 */
public final class TurnReasoningBuffer {

    private final StringBuilder buffer = new StringBuilder();

    /**
     * Appends a reasoning block. {@code null}/blank input is a no-op;
     * multiple blocks are separated by a blank line.
     */
    public synchronized void append(@Nullable String reasoning) {
        if (reasoning == null) return;
        String trimmed = reasoning.strip();
        if (trimmed.isEmpty()) return;
        if (buffer.length() > 0) buffer.append("\n\n");
        buffer.append(trimmed);
    }

    /** Accumulated reasoning, or {@code null} if nothing was captured. */
    public synchronized @Nullable String snapshot() {
        String out = buffer.toString().strip();
        return out.isEmpty() ? null : out;
    }

    /** Drop the buffer — for abort paths. */
    public synchronized void discard() {
        buffer.setLength(0);
    }
}
