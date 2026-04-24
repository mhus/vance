package de.mhus.vance.brain.events;

import java.util.function.Consumer;

/**
 * Buffers streaming text tokens and hands them off as larger chunks
 * so clients don't receive one frame per LLM token. Flushes when
 * either {@link #threshold} characters have accumulated or {@link
 * #flushIntervalMs} milliseconds have passed since the first unflushed
 * token arrived, whichever comes first.
 *
 * <p>Not thread-safe. A single batcher is owned by one streaming
 * turn and observed from exactly one provider-callback thread.
 */
public final class ChunkBatcher {

    private final StringBuilder buffer = new StringBuilder();
    private final Consumer<String> sink;
    private final int threshold;
    private final long flushIntervalMs;

    private long firstTokenAt = -1;

    public ChunkBatcher(int threshold, long flushIntervalMs, Consumer<String> sink) {
        this.threshold = Math.max(1, threshold);
        this.flushIntervalMs = Math.max(0, flushIntervalMs);
        this.sink = sink;
    }

    /** Adds {@code token} to the buffer and flushes if policy says so. */
    public void accept(String token) {
        if (token == null || token.isEmpty()) return;
        if (firstTokenAt < 0) {
            firstTokenAt = System.currentTimeMillis();
        }
        buffer.append(token);
        if (buffer.length() >= threshold) {
            flush();
            return;
        }
        if (flushIntervalMs > 0
                && System.currentTimeMillis() - firstTokenAt >= flushIntervalMs) {
            flush();
        }
    }

    /** Emits whatever is in the buffer, if anything. */
    public void flush() {
        if (buffer.length() == 0) return;
        String out = buffer.toString();
        buffer.setLength(0);
        firstTokenAt = -1;
        sink.accept(out);
    }
}
