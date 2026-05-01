package de.mhus.vance.foot.ui;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Tracks whether a chat round-trip (or any other "user is waiting on
 * the brain") operation is currently in flight. Independent of
 * {@link PromptGate} (which gates output rendering) — this one is
 * purely about user-visible "the brain is thinking" feedback.
 *
 * <p>Counter-based so overlapping requests stack correctly — the
 * indicator stays "busy" until every concurrent caller exits.
 */
@Component
public class BusyIndicator {

    private final AtomicInteger inFlight = new AtomicInteger();

    /** Mark a new in-flight operation. Pair with {@link #exit()} in a finally. */
    public void enter() {
        inFlight.incrementAndGet();
    }

    /** Decrement the in-flight counter. Idempotent at zero. */
    public void exit() {
        inFlight.updateAndGet(n -> n > 0 ? n - 1 : 0);
    }

    /**
     * Hard reset to "not busy" — used by user-driven halt commands
     * (ESC / {@code /pause} / {@code /stop}) so the animation goes
     * away immediately even though the underlying chat round-trip
     * may still be in flight on the WebSocket. Lingering
     * {@link #exit()} calls are absorbed by the {@code n > 0}
     * guard.
     */
    public void clear() {
        inFlight.set(0);
    }

    public boolean isBusy() {
        return inFlight.get() > 0;
    }

    /** Number of currently in-flight operations. Mostly for diagnostics. */
    public int depth() {
        return inFlight.get();
    }
}
