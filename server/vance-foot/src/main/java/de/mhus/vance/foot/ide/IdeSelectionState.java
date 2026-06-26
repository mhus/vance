package de.mhus.vance.foot.ide;

import de.mhus.vance.foot.ide.dto.Range;
import de.mhus.vance.foot.ide.dto.SelectionChanged;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Tracks the latest editor selection delivered by the Claude Code IDE
 * plugin's {@code selection_changed} notification. Subscribes to
 * {@link IdeNotificationDispatcher} on bean creation, exposes a
 * pre-formatted display string for the status bar.
 *
 * <p>Empty when no IDE bridge is connected, when no editor has focus, or
 * when the bridge has been disconnected — the state self-clears on
 * {@link #onConnectionStateChanged(boolean) false}, so the status bar
 * never shows stale info.
 */
@Component
public class IdeSelectionState implements IdeBridgeListener {

    private final IdeNotificationDispatcher dispatcher;
    private final AtomicReference<@Nullable Display> current = new AtomicReference<>();
    private final AtomicReference<@Nullable SelectionChanged> raw = new AtomicReference<>();
    private volatile Runnable repaintCallback = () -> {};

    public IdeSelectionState(IdeNotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * Wires a callback invoked whenever the display string actually
     * changes (new file, new range, cleared on disconnect). Used by the
     * REPL to request a live-region repaint — without this hook the
     * status bar only refreshes on the 10 s idle heartbeat, so IDE
     * selections appear stale.
     */
    public void setRepaintCallback(@Nullable Runnable callback) {
        this.repaintCallback = callback != null ? callback : () -> {};
    }

    @PostConstruct
    void subscribe() {
        dispatcher.register(this);
    }

    /**
     * Pre-rendered status string, e.g. {@code "⧉ foot-ui.md[5:13]"} or
     * {@code "⧉ foot-ui.md"}; {@link Optional#empty()} when nothing is
     * currently selected/visible.
     */
    public Optional<String> displayString() {
        Display d = current.get();
        return d == null ? Optional.empty() : Optional.of(d.text);
    }

    @Override
    public void onSelectionChanged(SelectionChanged sel) {
        Display before = current.get();
        if (sel.filePath() == null || sel.filePath().isBlank()) {
            current.set(null);
            raw.set(null);
        } else {
            String name = Path.of(sel.filePath()).getFileName().toString();
            String suffix = formatRange(sel.selection());
            current.set(new Display("⧉ " + name + suffix));
            raw.set(sel);
        }
        if (!sameText(before, current.get())) {
            fireRepaint();
        }
    }

    @Override
    public void onConnectionStateChanged(boolean connected) {
        if (!connected) {
            boolean hadState = current.get() != null;
            current.set(null);
            raw.set(null);
            if (hadState) fireRepaint();
        }
    }

    private void fireRepaint() {
        try {
            repaintCallback.run();
        } catch (RuntimeException ignored) {
            // never let a repaint failure poison the WS reader thread
        }
    }

    private static boolean sameText(@Nullable Display a, @Nullable Display b) {
        if (a == null) return b == null;
        return b != null && a.text.equals(b.text);
    }

    /**
     * Non-destructive snapshot of the current selection event for
     * downstream consumers (e.g. {@link IdeContextBuilder} attaching it
     * to the next steer). Empty when nothing is selected or no editor
     * has focus.
     */
    public Optional<SelectionChanged> snapshot() {
        return Optional.ofNullable(raw.get());
    }

    /**
     * Formats a 0-based plugin range as a 1-based human-readable suffix.
     * Empty string when there is no selection (cursor only, or null).
     * Single-line selection collapses to {@code "[5]"}; multi-line uses
     * {@code "[5:13]"}. {@code end.character == 0} is treated as
     * "selection ends at start of line N", so the highlighted region is
     * actually lines start..end-1 — Claude does the same adjustment.
     */
    static String formatRange(@Nullable Range range) {
        if (range == null) {
            return "";
        }
        // Caret check uses the raw range — a true caret has start == end
        // before any end-of-line adjustment is applied.
        if (range.start().line() == range.end().line()
                && range.start().character() == range.end().character()) {
            return "";
        }
        int startLine = range.start().line();
        int endLine = range.end().line();
        if (endLine > startLine && range.end().character() == 0) {
            endLine--;
        }
        if (endLine < startLine) {
            return "";
        }
        int displayStart = startLine + 1;
        int displayEnd = endLine + 1;
        if (displayStart == displayEnd) {
            return "[" + displayStart + "]";
        }
        return "[" + displayStart + ":" + displayEnd + "]";
    }

    private record Display(String text) {
    }
}
