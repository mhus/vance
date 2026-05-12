package de.mhus.vance.foot.ui;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.ide.IdeSelectionState;
import de.mhus.vance.foot.session.SessionService;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Pinned status line at the bottom of the JLine terminal. Always shows
 * the current session/project/active-process. Streaming previews are
 * rendered inline in the scrollback by {@link StreamingDisplay}, not
 * here — the bar stays a stable "where am I?" indicator.
 *
 * <p>While {@link BusyIndicator#isBusy()} is true (a chat round-trip
 * is in flight), a small bouncing-dot animation is appended to the
 * line so the user has visible feedback that something is running
 * even when the REPL prompt has already redrawn empty.
 */
@Component
public class StatusBar {

    private static final long ANIMATION_INTERVAL_MS = 120;

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
    private final AtomicReference<@Nullable Status> status = new AtomicReference<>();
    private final AtomicInteger frame = new AtomicInteger();
    private final AtomicReference<@Nullable ScheduledExecutorService> ticker =
            new AtomicReference<>();
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
     * Bind the bar to a JLine terminal — call from the REPL once the
     * terminal exists. Safe to re-call; replaces any prior binding.
     * Starts the background animation ticker.
     */
    public void attach(Terminal t) {
        if (!config.getUi().getStatusBar().isEnabled()) {
            return;
        }
        terminal.set(t);
        status.set(Status.getStatus(t));
        startTicker();
        repaint();
    }

    /** Drop the binding (REPL shutdown). Stops the ticker and clears the visible status. */
    public void detach() {
        stopTicker();
        Status s = status.getAndSet(null);
        if (s != null) {
            try {
                s.update(List.of());
            } catch (RuntimeException ignored) {
                // terminal may already be closing
            }
        }
        terminal.set(null);
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
            Thread t = new Thread(r, "foot-statusbar-ticker");
            t.setDaemon(true);
            return t;
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

    // ─── Painting ──────────────────────────────────────────────────

    private void repaint() {
        Status s = status.get();
        if (s == null) return;
        try {
            s.update(buildLines());
        } catch (RuntimeException ignored) {
            // Terminal could be tearing down — paint failure isn't fatal.
        }
    }

    /**
     * Composes the pinned status block. The line count is held
     * <strong>constant</strong> across repaints — variable shapes
     * (different number of lines from one update to the next) make
     * JLine's reserved-region accounting drift and push old rows into
     * scrollback as new ones arrive.
     *
     * <p>Layout, top-to-bottom:
     * <ol>
     *   <li>One reserved line for the IDE selection — empty when no
     *       selection / no bridge. Always present so its appearance /
     *       disappearance does not change the block height.</li>
     *   <li>One persistent line: session / process / spinner.</li>
     *   <li>{@code bottomPadding} blank trailing rows (default 4) — the
     *       cursor lives in this padding region so auto-scroll triggered
     *       on the bottom row never affects the spinner row.</li>
     * </ol>
     *
     * <p>Plan-mode todos used to be rendered above this block; they are
     * intentionally dropped from the pinned status while we stabilise
     * rendering in IntelliJ's terminal. They will return as a separate
     * Lanterna excursion or via {@code /plan} once the live-status flow
     * here is reliable.
     *
     * <p>Width clamp: every line is truncated to {@code terminalWidth - 1}
     * columns. A status line at exact terminal width auto-wraps to the
     * next row in many terminals (notably IntelliJ's built-in console),
     * which moves the cursor below the reserved region.
     */
    private List<AttributedString> buildLines() {
        int maxCols = safeWidth() - 1;
        List<AttributedString> out = new ArrayList<>();
        out.add(buildIdeSelectionLine());
        out.add(persistentLine());
        clampInPlace(out, maxCols);
        int padding = Math.max(0, config.getUi().getStatusBar().getBottomPadding());
        for (int i = 0; i < padding; i++) {
            out.add(AttributedString.EMPTY);
        }
        return out;
    }

    /**
     * Builds a single fixed-position row for the current IDE selection.
     * Returns {@link AttributedString#EMPTY} when nothing is selected /
     * the bridge is offline — the row is reserved either way so repaints
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

    private int safeWidth() {
        Terminal t = terminal.get();
        if (t == null) return 80;
        int w = t.getWidth();
        return w > 0 ? w : 80;
    }

    /**
     * Truncates each non-empty line to {@code maxCols} characters, keeping
     * its style. {@code maxCols <= 0} disables the clamp (degenerate
     * terminal width — let JLine cope as best it can). Empty lines pass
     * through untouched so the trailing pad rows remain blank.
     */
    private static void clampInPlace(List<AttributedString> lines, int maxCols) {
        if (maxCols <= 0) return;
        for (int i = 0; i < lines.size(); i++) {
            AttributedString line = lines.get(i);
            if (line.length() > maxCols) {
                lines.set(i, line.subSequence(0, maxCols));
            }
        }
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
}
