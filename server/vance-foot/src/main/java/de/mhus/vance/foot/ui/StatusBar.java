package de.mhus.vance.foot.ui;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.ide.IdeSelectionState;
import de.mhus.vance.foot.session.SessionService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Pinned status block at the bottom of the JLine terminal. Two lines
 * (IDE selection + session/process/spinner) plus an empty safety row.
 *
 * <p>This replaces JLine's {@code Status} API with a manual ANSI painter
 * driven by a write lock — JLine's {@code Status} drifted upward in
 * IntelliJ's built-in terminal because absolute cursor positioning
 * outside the active DECSTBM region was being clamped back into the
 * scroll region. See {@code readme/foot-status-bar-rendering.md} for
 * the full investigation.
 *
 * <p>Layout (terminal height {@code H}, {@code P = max(1, bottomPadding)}):
 * <pre>
 *   rows 1 .. H-(2+P)     scrollable content (DECSTBM active)
 *   row  H-(1+P)          IDE selection line
 *   row  H-P              session / process / spinner
 *   rows H-(P-1) .. H     reserved blank padding (≥1 row safety)
 * </pre>
 *
 * <p>Paint protocol per repaint (animator thread, under {@link #writeLock}):
 * <pre>
 *   ESC[s                 save cursor
 *   ESC[r                 reset DECSTBM (cursor unclamped)
 *   ESC[selectionRow;1H ESC[2K &lt;selection&gt;
 *   ESC[persistentRow;1H ESC[2K &lt;persistent&gt;
 *   ESC[0m
 *   ESC[1;scrollEndRow r  re-arm DECSTBM
 *   ESC[u                 restore cursor
 * </pre>
 *
 * <p>Async content (e.g. {@code printAbove} from ChatTerminal) must
 * synchronise on {@link #writeLock} too, or the two threads will
 * interleave escape sequences and corrupt the cursor state.
 */
@Component
public class StatusBar {

    /** ESC byte as a Java Unicode escape — survives source-file roundtrips
     * that strip raw {@code 0x1b} bytes from string literals. */
    private static final String ESC = "\u001b";

    private static final long ANIMATION_INTERVAL_MS = 120;

    /** Number of status text rows (selection + persistent). */
    private static final int STATUS_ROWS = 2;

    /** 9-frame bouncing-dot animation — left, right, back. */
    private static final String[] FRAMES = {
            "●○○○○",
            "○●○○○",
            "○○●○○",
            "○○○●○",
            "○○○○●",
            "○○○●○",
            "○○●○○",
            "○●○○○"
    };

    private final SessionService sessions;
    private final BusyIndicator busy;
    private final ThinkingPhrases phrases;
    private final ObjectProvider<IdeSelectionState> ideSelection;
    private final FootConfig config;
    private final AtomicReference<@Nullable Terminal> terminal = new AtomicReference<>();
    private final AtomicInteger frame = new AtomicInteger();
    private final AtomicReference<@Nullable ScheduledExecutorService> ticker =
            new AtomicReference<>();

    /**
     * Single lock guarding all writes to {@code terminal.output()} that
     * touch escape sequences — both the status painter here and the
     * async chat output through {@link ChatTerminal} must acquire it.
     * Acquire it for the entire span of an ANSI sequence so the two
     * threads cannot interleave bytes.
     */
    private final Object writeLock = new Object();

    /** True when the last paint included the busy spinner — drives "clear once" on idle. */
    private volatile boolean lastPaintedBusy = false;
    /** Last IDE selection text we painted; drives "repaint on selection change". */
    private volatile String lastPaintedSelection = "";
    /**
     * Phrase chosen at the most recent idle → busy transition. Stable
     * for the whole busy period so it doesn't strobe through quotes.
     */
    private volatile String currentPhrase = "thinking";

    public StatusBar(SessionService sessions,
                     BusyIndicator busy,
                     ThinkingPhrases phrases,
                     ObjectProvider<IdeSelectionState> ideSelection,
                     FootConfig config) {
        this.sessions = sessions;
        this.busy = busy;
        this.phrases = phrases;
        this.ideSelection = ideSelection;
        this.config = config;
    }

    /**
     * The lock that gates all escape-sequence writes. Other components
     * (chiefly {@link ChatTerminal} when emitting async output via
     * {@code printAbove}) must synchronise on this before touching the
     * terminal's writer, or the two threads' escape sequences will
     * interleave and corrupt the cursor state.
     */
    public Object writeLock() {
        return writeLock;
    }

    /**
     * Bind the bar to a JLine terminal — call from the REPL once the
     * terminal exists. Sets up the DECSTBM scroll region so chat output
     * stays above the reserved rows and starts the animation ticker.
     */
    public void attach(Terminal t) {
        if (!config.getUi().getStatusBar().isEnabled()) {
            return;
        }
        terminal.set(t);
        applyScrollRegion(t);
        startTicker();
        repaint();
    }

    /** Drop the binding (REPL shutdown). Clears the status rows and releases DECSTBM. */
    public void detach() {
        stopTicker();
        Terminal t = terminal.getAndSet(null);
        if (t == null) {
            return;
        }
        teardownScrollRegion(t);
    }

    /** Re-read the persistent fields from {@link SessionService} and repaint. */
    public void refresh() {
        repaint();
    }

    @PreDestroy
    void shutdown() {
        stopTicker();
    }

    // ─── Ticker ────────────────────────────────────────────────────

    private void startTicker() {
        ScheduledExecutorService existing = ticker.get();
        if (existing != null && !existing.isShutdown()) {
            return;
        }
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "foot-statusbar-ticker");
            th.setDaemon(true);
            return th;
        });
        exec.scheduleAtFixedRate(this::tick,
                ANIMATION_INTERVAL_MS, ANIMATION_INTERVAL_MS, TimeUnit.MILLISECONDS);
        ticker.set(exec);
    }

    private void stopTicker() {
        ScheduledExecutorService exec = ticker.getAndSet(null);
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    private void tick() {
        boolean nowBusy = busy.isBusy();
        boolean animated = config.getUi().getStatusBar().isAnimated();
        String selectionNow = ideSelectionText();
        boolean selectionChanged = !selectionNow.equals(lastPaintedSelection);
        if (nowBusy) {
            if (!lastPaintedBusy) {
                currentPhrase = phrases.random();
                frame.set(0);
                repaint();
            } else if (animated || selectionChanged) {
                if (animated) frame.incrementAndGet();
                repaint();
            }
        } else if (lastPaintedBusy || selectionChanged) {
            repaint();
        }
    }

    private String ideSelectionText() {
        IdeSelectionState state = ideSelection.getIfAvailable();
        return state == null ? "" : state.displayString().orElse("");
    }

    // ─── Scroll region setup / teardown ────────────────────────────

    private void applyScrollRegion(Terminal t) {
        int h = t.getHeight();
        int reserved = reservedRows();
        if (h < reserved + 2) {
            return; // terminal too small — degrade gracefully
        }
        int scrollEnd = h - reserved;
        synchronized (writeLock) {
            writeRaw(t, ESC + "[1;" + scrollEnd + "r");
            // Park cursor at the bottom of the scroll region so the first
            // emit scrolls naturally instead of overwriting row 1.
            writeRaw(t, ESC + "[" + scrollEnd + ";1H");
        }
    }

    private void teardownScrollRegion(Terminal t) {
        int h = t.getHeight();
        int reserved = reservedRows();
        if (h < reserved + 2) {
            return;
        }
        synchronized (writeLock) {
            StringBuilder sb = new StringBuilder();
            sb.append(ESC).append("[s");
            sb.append(ESC).append("[r");
            // Clear all reserved rows so we don't leave status text behind
            // when the shell prompt comes back.
            for (int row = h - reserved + 1; row <= h; row++) {
                sb.append(ESC).append("[").append(row).append(";1H");
                sb.append(ESC).append("[2K");
            }
            sb.append(ESC).append("[u");
            sb.append("\r\n"); // own line for the shell prompt
            writeRaw(t, sb.toString());
        }
    }

    private int reservedRows() {
        int padding = Math.max(1, config.getUi().getStatusBar().getBottomPadding());
        return STATUS_ROWS + padding;
    }

    // ─── Painting ──────────────────────────────────────────────────

    private void repaint() {
        Terminal t = terminal.get();
        if (t == null) return;
        int h = t.getHeight();
        int w = t.getWidth();
        int reserved = reservedRows();
        if (h < reserved + 2 || w < 10) {
            return;
        }
        int scrollEnd = h - reserved;
        int selectionRow = scrollEnd + 1;
        int persistentRow = scrollEnd + 2;

        int maxCols = w - 1;
        String selectionAnsi = clamp(buildIdeSelectionLine(), maxCols).toAnsi(t);
        String persistentAnsi = clamp(persistentLine(), maxCols).toAnsi(t);

        StringBuilder sb = new StringBuilder();
        sb.append(ESC).append("[s");
        sb.append(ESC).append("[r");                    // reset DECSTBM (unclamp cursor)
        sb.append(ESC).append("[").append(selectionRow).append(";1H");
        sb.append(ESC).append("[2K");
        sb.append(selectionAnsi);
        sb.append(ESC).append("[0m");
        sb.append(ESC).append("[").append(persistentRow).append(";1H");
        sb.append(ESC).append("[2K");
        sb.append(persistentAnsi);
        sb.append(ESC).append("[0m");
        sb.append(ESC).append("[1;").append(scrollEnd).append("r"); // re-arm DECSTBM
        sb.append(ESC).append("[u");                    // restore cursor

        synchronized (writeLock) {
            writeRaw(t, sb.toString());
        }
    }

    /**
     * Builds a single fixed-position row for the current IDE selection.
     * Returns {@link AttributedString#EMPTY} when nothing is selected /
     * the bridge is offline — the row is reserved either way so paints
     * do not change the block height.
     */
    private AttributedString buildIdeSelectionLine() {
        IdeSelectionState state = ideSelection.getIfAvailable();
        if (state == null) {
            return AttributedString.EMPTY;
        }
        return state.displayString()
                .map(text -> new AttributedStringBuilder()
                        .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN))
                        .append(text)
                        .toAttributedString())
                .orElse(AttributedString.EMPTY);
    }

    private AttributedString persistentLine() {
        AttributedStringBuilder b = new AttributedStringBuilder();
        b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            b.append(" ── no session ── ");
        } else {
            // Icon + title prefix (emoji terminals support them; if a
            // terminal can't render the codepoint, it shows a tofu box,
            // which the user can avoid by clearing the icon).
            if (bound.icon() != null && !bound.icon().isBlank()) {
                b.append(' ').append(bound.icon());
            }
            if (bound.title() != null && !bound.title().isBlank()) {
                b.append(' ').append(bound.title());
                b.append("  · ");
            } else {
                b.append(' ');
            }
            b.append("session=").append(bound.sessionId());
            b.append("  project=").append(bound.projectId());
            String active = sessions.activeProcess();
            b.append("  process=").append(active == null ? "—" : active);
        }
        boolean isBusy = busy.isBusy();
        if (isBusy) {
            String marker = config.getUi().getStatusBar().isAnimated()
                    ? FRAMES[Math.floorMod(frame.get(), FRAMES.length)]
                    : "●";
            b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
            b.append("  ").append(currentPhrase).append(' ').append(marker);
        }
        lastPaintedBusy = isBusy;
        lastPaintedSelection = ideSelectionText();
        return b.toAttributedString();
    }

    private static AttributedString clamp(AttributedString line, int maxCols) {
        if (maxCols <= 0 || line.length() <= maxCols) {
            return line;
        }
        return line.subSequence(0, maxCols);
    }

    private static void writeRaw(Terminal t, String s) {
        try {
            OutputStream out = t.output();
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // Terminal may already be tearing down — paint failure isn't fatal.
        }
    }
}
