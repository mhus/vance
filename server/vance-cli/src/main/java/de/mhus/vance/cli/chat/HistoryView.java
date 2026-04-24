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

    public HistoryView(String name) {
        this(name, DEFAULT_CAPACITY);
    }

    public HistoryView(String name, int capacity) {
        super(name, 1, 1);
        this.capacity = Math.max(16, capacity);
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

    /** Drops all lines. Safe to call from any thread. */
    public void clear() {
        synchronized (lock) {
            lines.clear();
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

        // Render from the bottom up so the newest line always sits on the last row.
        int row = h - 1;
        for (int i = snapshot.size() - 1; i >= 0 && row >= 0; i--) {
            ChatLine line = snapshot.get(i);
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
