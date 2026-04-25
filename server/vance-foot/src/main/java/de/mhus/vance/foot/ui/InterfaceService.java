package de.mhus.vance.foot.ui;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.terminal.Terminal;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * UI mode controller. Tracks whether the {@link UiMode#CHAT} REPL or a
 * {@link UiMode#FULLSCREEN} Lanterna excursion currently owns the terminal.
 *
 * <p>The hybrid pattern: JLine and Lanterna cannot share the TTY at the same
 * time. {@code runFullscreen} pauses JLine, runs the supplied Lanterna
 * interaction in the alternate screen buffer, and resumes JLine on return —
 * even on exception or interrupt.
 */
@Service
public class InterfaceService {

    private final AtomicReference<UiMode> mode = new AtomicReference<>(UiMode.CHAT);
    private final AtomicReference<@Nullable Terminal> jlineTerminal = new AtomicReference<>();

    public UiMode mode() {
        return mode.get();
    }

    /** Called by {@link ChatRepl} once it has constructed its terminal. */
    public void registerJlineTerminal(Terminal terminal) {
        jlineTerminal.set(terminal);
    }

    public void clearJlineTerminal() {
        jlineTerminal.set(null);
    }

    /**
     * Runs a Lanterna excursion. JLine is paused, Lanterna takes over the
     * alternate screen buffer, control returns when {@code excursion} exits.
     * The mode is restored to {@link UiMode#CHAT} regardless of how the
     * excursion finished.
     */
    public void runFullscreen(LanternaExcursion excursion) throws IOException {
        Terminal t = jlineTerminal.get();
        if (t == null) {
            throw new IllegalStateException(
                    "No JLine terminal registered — start the REPL before running a fullscreen excursion.");
        }
        if (!mode.compareAndSet(UiMode.CHAT, UiMode.FULLSCREEN)) {
            throw new IllegalStateException(
                    "A fullscreen excursion is already active — nested Lanterna sessions are not supported.");
        }
        t.pause();
        try (LanternaSession session = LanternaSession.open()) {
            excursion.run(session);
        } finally {
            t.resume();
            mode.set(UiMode.CHAT);
        }
    }

    /** Closure executed inside an active Lanterna session. */
    @FunctionalInterface
    public interface LanternaExcursion {
        void run(LanternaSession session) throws IOException;
    }
}
