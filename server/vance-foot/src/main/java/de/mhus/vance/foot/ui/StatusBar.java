package de.mhus.vance.foot.ui;

import de.mhus.vance.foot.session.SessionService;
import jakarta.annotation.PreDestroy;
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
    private final AtomicReference<@Nullable Terminal> terminal = new AtomicReference<>();
    private final AtomicReference<@Nullable Status> status = new AtomicReference<>();
    private final AtomicInteger frame = new AtomicInteger();
    private final AtomicReference<@Nullable ScheduledExecutorService> ticker =
            new AtomicReference<>();
    /** True when the last paint included the busy spinner — drives "clear once" on idle. */
    private volatile boolean lastPaintedBusy = false;
    /**
     * Phrase chosen at the most recent idle → busy transition. Stable
     * for the whole busy period so it doesn't strobe through quotes.
     */
    private volatile String currentPhrase = "thinking";

    public StatusBar(SessionService sessions, BusyIndicator busy, ThinkingPhrases phrases) {
        this.sessions = sessions;
        this.busy = busy;
        this.phrases = phrases;
    }

    /**
     * Bind the bar to a JLine terminal — call from the REPL once the
     * terminal exists. Safe to re-call; replaces any prior binding.
     * Starts the background animation ticker.
     */
    public void attach(Terminal t) {
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
        if (nowBusy) {
            // Idle → busy transition: pick a fresh phrase. Keep it
            // stable for the rest of this busy period — strobe-y
            // changes per frame would be hard to read.
            if (!lastPaintedBusy) {
                currentPhrase = phrases.random();
                frame.set(0);
            } else {
                frame.incrementAndGet();
            }
            repaint();
        } else if (lastPaintedBusy) {
            // Just transitioned busy → idle — paint once to drop the spinner.
            repaint();
        }
        // Idle and already-clean: no-op, save the syscalls.
    }

    // ─── Painting ──────────────────────────────────────────────────

    private void repaint() {
        Status s = status.get();
        if (s == null) return;
        try {
            s.update(List.of(persistentLine()));
        } catch (RuntimeException ignored) {
            // Terminal could be tearing down — paint failure isn't fatal.
        }
    }

    private AttributedString persistentLine() {
        AttributedStringBuilder b = new AttributedStringBuilder();
        b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            b.append(" ── no session ── ");
        } else {
            b.append(" session=").append(bound.sessionId());
            b.append("  project=").append(bound.projectId());
            String active = sessions.activeProcess();
            b.append("  process=").append(active == null ? "—" : active);
        }
        boolean isBusy = busy.isBusy();
        if (isBusy) {
            String f = FRAMES[Math.floorMod(frame.get(), FRAMES.length)];
            b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
            b.append("  ").append(currentPhrase).append(' ').append(f);
        }
        lastPaintedBusy = isBusy;
        return b.toAttributedString();
    }
}
