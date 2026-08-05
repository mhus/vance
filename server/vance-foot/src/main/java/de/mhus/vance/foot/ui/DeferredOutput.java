package de.mhus.vance.foot.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Bounded backlog of static output captured while the TTY is on loan to
 * another consumer — in practice a Lanterna fullscreen excursion
 * ({@link InterfaceService#runFullscreen}).
 *
 * <p>JLine and Lanterna cannot share the terminal, and neither can our
 * live region: a chat line written into Lanterna's alternate screen
 * buffer corrupts it permanently, because Lanterna repaints deltas only
 * and never learns about the foreign bytes. So while a fullscreen UI is
 * up, everything the brain pushes at us lands here instead and is
 * replayed into the scrollback once JLine takes the terminal back.
 *
 * <p>Bounded with drop-oldest: a long-running build streaming into the
 * chat must not grow this unbounded while the user reads an exec log.
 * The number of dropped entries is reported in the flush marker rather
 * than swallowed.
 */
final class DeferredOutput {

    /**
     * Entries kept before the oldest are dropped. Well above the
     * {@link ChatTerminal} scrollback ring — a fullscreen excursion is a
     * human-length pause, and losing the head of an assistant turn is
     * worse than holding a few hundred KB.
     */
    static final int DEFAULT_LIMIT = 2_000;

    private final int limit;
    private final Deque<String> entries = new ArrayDeque<>();
    private int dropped;

    DeferredOutput() {
        this(DEFAULT_LIMIT);
    }

    DeferredOutput(int limit) {
        this.limit = Math.max(1, limit);
    }

    /** One drained backlog: the captured entries plus how many were lost. */
    record Batch(List<String> entries, int dropped) {
        boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    /** Appends one entry, dropping the oldest when the cap is reached. */
    synchronized void add(String text) {
        if (text == null || text.isEmpty()) return;
        while (entries.size() >= limit) {
            entries.removeFirst();
            dropped++;
        }
        entries.addLast(text);
    }

    synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Removes and returns everything captured so far. */
    synchronized Batch drain() {
        Batch batch = new Batch(List.copyOf(entries), dropped);
        entries.clear();
        dropped = 0;
        return batch;
    }

    /** Discards the backlog — used when the user clears the screen. */
    synchronized void clear() {
        entries.clear();
        dropped = 0;
    }

    /**
     * Header line printed above a replayed backlog so the jump in the
     * scrollback is explained instead of looking like a glitch.
     */
    static String marker(int count, int dropped) {
        StringBuilder sb = new StringBuilder("— ");
        sb.append(count).append(count == 1 ? " line" : " lines")
          .append(" arrived while the fullscreen UI was open");
        if (dropped > 0) {
            sb.append(" (").append(dropped)
              .append(dropped == 1 ? " older line dropped)" : " older lines dropped)");
        }
        return sb.append(" —").toString();
    }
}
