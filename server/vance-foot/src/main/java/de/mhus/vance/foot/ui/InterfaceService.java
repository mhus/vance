package de.mhus.vance.foot.ui;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.terminal.Terminal;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * UI mode controller. Tracks whether the {@link UiMode#CHAT} REPL or a
 * {@link UiMode#FULLSCREEN} Lanterna excursion currently owns the terminal.
 *
 * <p>The hybrid pattern: the live JLine-backed region and Lanterna cannot
 * consume the TTY at the same time. {@code runFullscreen} pauses the live
 * region, runs the supplied Lanterna interaction in the alternate screen
 * buffer, and resumes the region on return — even on exception or interrupt.
 *
 * <p>Exactly one of the two surfaces is live, and that includes output:
 * while the excursion runs, background text (chat turns pushed by the
 * brain, worker echoes) is buffered by {@link LiveRegion#pause()} and
 * becomes visible when the REPL takes the terminal back.
 */
@Service
public class InterfaceService {

    private final AtomicReference<UiMode> mode = new AtomicReference<>(UiMode.CHAT);
    private final AtomicReference<@Nullable Terminal> jlineTerminal = new AtomicReference<>();
    private final LiveRegion liveRegion;

    public InterfaceService(@Lazy LiveRegion liveRegion) {
        this.liveRegion = liveRegion;
    }

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
     * Runs a Lanterna excursion. The live region releases input, Lanterna
     * takes over the alternate screen buffer, and the region reclaims input
     * when {@code excursion} exits. The mode is restored to
     * {@link UiMode#CHAT} regardless of how the excursion finished.
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
        // LiveRegion still has our soft-raw mode + active input/animator
        // threads. Tell it to pause before we hand the TTY to Lanterna,
        // otherwise our reader keeps eating bytes that Lanterna needs to
        // initialise its own terminal (typical symptom: EOFException).
        //
        // Unconditional, also when the region isn't attached: pause()
        // doubles as the output gate. Everything the brain pushes at us
        // during the excursion (chat turns, worker echoes, streaming
        // chunks) is buffered there and replayed by resume() — a write
        // into Lanterna's alternate screen buffer would corrupt it for
        // good, since Lanterna only ever repaints deltas.
        liveRegion.pause();
        // LiveRegion reads from JLine's Terminal.reader(). It is therefore
        // itself the only input consumer; pausing the Terminal's internal
        // pump would interrupt the very reader we want to reuse afterwards
        // and can leave its decoder between ESC bytes. Lanterna temporarily
        // owns System.in only after LiveRegion's reader thread has stopped.
        try (LanternaSession session = LanternaSession.open()) {
            excursion.run(session);
        } finally {
            try {
                liveRegion.resume();
            } finally {
                mode.set(UiMode.CHAT);
            }
        }
    }

    /** Closure executed inside an active Lanterna session. */
    @FunctionalInterface
    public interface LanternaExcursion {
        void run(LanternaSession session) throws IOException;
    }
}
