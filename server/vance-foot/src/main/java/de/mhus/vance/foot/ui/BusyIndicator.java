package de.mhus.vance.foot.ui;

import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Tracks whether a chat round-trip (or any other "user is waiting on
 * the brain") operation is currently in flight. Independent of
 * {@link PromptGate} (which gates output rendering) — this one is
 * purely about user-visible "the brain is thinking" feedback.
 *
 * <p>Counter-based so overlapping requests stack correctly — the
 * indicator stays "busy" until every concurrent caller exits.
 *
 * <p>Two callers feed the counter:
 * <ul>
 *   <li>{@link de.mhus.vance.foot.command.ChatInputService} —
 *       around the synchronous WS round-trip for {@code /chat}.</li>
 *   <li>{@link de.mhus.vance.foot.connection.handlers.ProcessProgressHandler}
 *       — on every {@code engine_turn_start} / {@code engine_turn_end}
 *       progress message. This keeps the spinner alive while async
 *       worker turns run after the original chat round-trip has
 *       returned.</li>
 * </ul>
 *
 * <p>Every state change is logged at INFO with a short {@code source}
 * tag so the test harness (and humans tailing {@code foot.log}) can
 * verify the spinner-lifecycle independently of the rendering layer.
 */
@Component
@Slf4j
public class BusyIndicator {

    private final AtomicInteger inFlight = new AtomicInteger();

    /**
     * Mark a new in-flight operation. Pair with {@link #exit(String)} in a
     * finally. {@code source} is a free-form short label (e.g.
     * {@code "chat-roundtrip"}, {@code "engine_turn_start:web-research-x"})
     * for log-trail diagnostics.
     */
    public void enter(String source) {
        int depth = inFlight.incrementAndGet();
        log.info("BUSY enter source='{}' depth={}", source, depth);
    }

    /** Decrement the in-flight counter. Idempotent at zero. */
    public void exit(String source) {
        int depth = inFlight.updateAndGet(n -> n > 0 ? n - 1 : 0);
        log.info("BUSY exit source='{}' depth={}", source, depth);
    }

    /**
     * Hard reset to "not busy" — used by user-driven halt commands
     * (ESC / {@code /pause} / {@code /stop}) so the animation goes
     * away immediately even though the underlying chat round-trip
     * may still be in flight on the WebSocket. Lingering
     * {@link #exit(String)} calls are absorbed by the {@code n > 0}
     * guard.
     */
    public void clear() {
        int prior = inFlight.getAndSet(0);
        if (prior > 0) {
            log.info("BUSY clear (prior depth={})", prior);
        }
    }

    public boolean isBusy() {
        return inFlight.get() > 0;
    }

    /** Number of currently in-flight operations. Mostly for diagnostics. */
    public int depth() {
        return inFlight.get();
    }
}
