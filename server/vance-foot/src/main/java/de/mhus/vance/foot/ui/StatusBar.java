package de.mhus.vance.foot.ui;

import de.mhus.vance.foot.session.SessionService;
import java.util.List;
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
 */
@Component
public class StatusBar {

    private final SessionService sessions;
    private final AtomicReference<@Nullable Terminal> terminal = new AtomicReference<>();
    private final AtomicReference<@Nullable Status> status = new AtomicReference<>();

    public StatusBar(SessionService sessions) {
        this.sessions = sessions;
    }

    /**
     * Bind the bar to a JLine terminal — call from the REPL once the
     * terminal exists. Safe to re-call; replaces any prior binding.
     */
    public void attach(Terminal t) {
        terminal.set(t);
        status.set(Status.getStatus(t));
        repaint();
    }

    /** Drop the binding (REPL shutdown). Clears the visible status. */
    public void detach() {
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
        return b.toAttributedString();
    }
}
