package de.mhus.vance.cli.chat;

import com.consolemaster.AnsiColor;
import com.consolemaster.Canvas;
import com.consolemaster.Graphics;
import java.util.ArrayList;
import java.util.List;

/**
 * Scrollable history panel. Stores a bounded list of {@link ChatLine}s and
 * paints the tail that fits into the visible area — newest line at the bottom,
 * oldest drops off the top. Thread-safe for append from non-UI threads (the
 * WebSocket listener runs in its own executor).
 */
public class HistoryView extends Canvas {

    private static final int DEFAULT_CAPACITY = 2000;

    private final List<ChatLine> lines = new ArrayList<>();
    private final int capacity;
    private final Object lock = new Object();
    private volatile int verbosity = 1;

    public HistoryView(String name) {
        this(name, DEFAULT_CAPACITY);
    }

    public HistoryView(String name, int capacity) {
        super(name, 1, 1);
        this.capacity = Math.max(16, capacity);
    }

    /**
     * Current runtime verbosity threshold. Lines whose {@link ChatLine.Level
     * #minVerbosity()} exceeds this value are hidden in {@link #paint}; the
     * underlying history is kept so bumping the level later surfaces
     * previously-hidden lines retroactively.
     */
    public int verbosity() {
        return verbosity;
    }

    /** Safe to call from any thread. */
    public void setVerbosity(int level) {
        this.verbosity = Math.max(0, level);
    }

    /** Appends a line. Safe to call from any thread. */
    public void append(ChatLine line) {
        synchronized (lock) {
            lines.add(line);
            if (lines.size() > capacity) {
                lines.subList(0, lines.size() - capacity).clear();
            }
        }
    }

    /**
     * Replaces the most recent line at {@code level} — scanning
     * backward so intervening diagnostic lines (e.g. WIRE traces)
     * don't break the in-place update — or appends a new one if none
     * exists. Safe to call from any thread.
     */
    public void replaceOrAppend(ChatLine.Level level, ChatLine line) {
        synchronized (lock) {
            int idx = lastIndexOfLevel(level);
            if (idx >= 0) {
                lines.set(idx, line);
                return;
            }
            lines.add(line);
            if (lines.size() > capacity) {
                lines.subList(0, lines.size() - capacity).clear();
            }
        }
    }

    /**
     * Removes the most recent line at {@code level}. Returns
     * {@code true} if one was removed. Scans backward so interleaved
     * diagnostic lines don't hide the target. Used to discard the
     * streaming preview once the canonical message arrives.
     */
    public boolean removeLastIfLevel(ChatLine.Level level) {
        synchronized (lock) {
            int idx = lastIndexOfLevel(level);
            if (idx < 0) return false;
            lines.remove(idx);
            return true;
        }
    }

    private int lastIndexOfLevel(ChatLine.Level level) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).level() == level) return i;
        }
        return -1;
    }

    /** Drops all lines. Safe to call from any thread. */
    public void clear() {
        synchronized (lock) {
            lines.clear();
        }
    }

    /** Returns an immutable snapshot of the current lines. Safe to call from any thread. */
    public List<ChatLine> snapshot() {
        synchronized (lock) {
            return List.copyOf(lines);
        }
    }

    /**
     * Returns up to {@code limit} most-recent lines in insertion order
     * (oldest first). Safe to call from any thread.
     */
    public List<ChatLine> tail(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        synchronized (lock) {
            int from = Math.max(0, lines.size() - limit);
            return List.copyOf(lines.subList(from, lines.size()));
        }
    }

    @Override
    public void paint(Graphics graphics) {
        graphics.clear();
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        List<ChatLine> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(lines);
        }

        int v = verbosity;

        // Render from the bottom up so the newest line always sits on the last row.
        int row = h - 1;
        for (int i = snapshot.size() - 1; i >= 0 && row >= 0; i--) {
            ChatLine line = snapshot.get(i);
            if (line.level().minVerbosity() > v) {
                continue;
            }
            List<String> wrapped = wrap(renderLine(line), w);
            for (int j = wrapped.size() - 1; j >= 0 && row >= 0; j--) {
                graphics.drawStyledString(0, row, pad(wrapped.get(j), w),
                        line.level().color(), null);
                row--;
            }
        }

        // Clear any rows above the content with blanks in default style.
        while (row >= 0) {
            graphics.drawStyledString(0, row, pad("", w), AnsiColor.WHITE, null);
            row--;
        }
    }

    private static String renderLine(ChatLine line) {
        return line.formattedTimestamp() + " " + line.level().prefix() + " " + line.text();
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            out.add("");
            return out;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + width, text.length());
            out.add(text.substring(start, end));
            start = end;
        }
        return out;
    }

    private static String pad(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        StringBuilder sb = new StringBuilder(width);
        sb.append(text);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
