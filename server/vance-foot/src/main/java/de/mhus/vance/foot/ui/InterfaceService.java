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
 * <p>The hybrid pattern: JLine and Lanterna cannot share the TTY at the same
 * time. {@code runFullscreen} pauses JLine, runs the supplied Lanterna
 * interaction in the alternate screen buffer, and resumes JLine on return —
 * even on exception or interrupt.
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
        // LiveRegion still has our soft-raw mode + active input/animator
        // threads. Tell it to pause before we hand the TTY to Lanterna,
        // otherwise our reader keeps eating bytes that Lanterna needs to
        // initialise its own terminal (typical symptom: EOFException).
        boolean liveWasAttached = liveRegion.isAttached();
        if (liveWasAttached) {
            liveRegion.pause();
        }
        // pause(true) joins JLine's input pump thread before Lanterna
        // takes over System.in — without the join JLine keeps pumping
        // bytes into its NonBlockingReader's char buffer, leaving it
        // mid-decode when Lanterna grabs the TTY. After resume() that
        // half-decoded state surfaces as BufferUnderflowException on
        // the next multi-byte read (e.g. an Esc-sequence from an
        // arrow key).
        try {
            t.pause(true);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (liveWasAttached) liveRegion.resume();
            mode.set(UiMode.CHAT);
            throw new IOException("Interrupted while pausing JLine for fullscreen excursion", ie);
        }
        try (LanternaSession session = LanternaSession.open()) {
            excursion.run(session);
        } finally {
            t.resume();
            if (liveWasAttached) {
                liveRegion.resume();
            }
            mode.set(UiMode.CHAT);
        }
    }

    /** Closure executed inside an active Lanterna session. */
    @FunctionalInterface
    public interface LanternaExcursion {
        void run(LanternaSession session) throws IOException;
    }
}
