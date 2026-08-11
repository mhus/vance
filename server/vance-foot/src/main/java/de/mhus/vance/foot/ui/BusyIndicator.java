package de.mhus.vance.foot.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
     * Keys of the currently-open keyed operations (see
     * {@link #enterKeyed(String, String)}). Lives here rather than in the
     * caller so {@link #clear()} resets counter <em>and</em> key set in one
     * step — a split between the two is unrecoverable: a key left behind
     * after a clear() de-duplicates the next open away and the indicator
     * can never go busy again.
     */
    private final Set<String> openKeys = new HashSet<>();

    /**
     * Edge listeners (e.g. the sleep inhibitor). Notified only on the
     * 0↔1 boundary transitions, never on nested enter/exit.
     */
    private final List<BusyListener> listeners;

    /** No-arg constructor for unit tests that exercise the counter in isolation. */
    public BusyIndicator() {
        this(List.of());
    }

    @Autowired
    public BusyIndicator(List<BusyListener> listeners) {
        this.listeners = listeners;
    }

    /**
     * Mark a new in-flight operation. Pair with {@link #exit(String)} in a
     * finally. {@code source} is a free-form short label (e.g.
     * {@code "chat-roundtrip"}, {@code "engine_turn_start:web-research-x"})
     * for log-trail diagnostics.
     */
    public void enter(String source) {
        int depth = inFlight.incrementAndGet();
        log.info("BUSY enter source='{}' depth={}", source, depth);
        if (depth == 1) {
            fire(true);
        }
    }

    /** Decrement the in-flight counter. Idempotent at zero. */
    public void exit(String source) {
        int depth = inFlight.updateAndGet(n -> n > 0 ? n - 1 : 0);
        log.info("BUSY exit source='{}' depth={}", source, depth);
        if (depth == 0) {
            fire(false);
        }
    }

    /**
     * Enter for an operation with a natural identity (e.g. one engine
     * turn per think-process). A second enter for the same {@code key}
     * is ignored, so duplicate open events — WS retries, a reconnect
     * replaying a start — cannot inflate the counter.
     *
     * @return {@code true} when this call actually opened the key
     */
    public boolean enterKeyed(String key, String source) {
        synchronized (openKeys) {
            if (!openKeys.add(key)) {
                log.trace("BUSY enterKeyed ignored — key='{}' already open", key);
                return false;
            }
            enter(source);
            return true;
        }
    }

    /**
     * Exit for a key opened by {@link #enterKeyed(String, String)}.
     * Ignored when the key isn't open — an unpaired close event (or one
     * arriving after a {@link #clear()}) must not push the counter
     * below the work that is genuinely in flight.
     *
     * @return {@code true} when this call actually closed the key
     */
    public boolean exitKeyed(String key, String source) {
        synchronized (openKeys) {
            if (!openKeys.remove(key)) {
                log.trace("BUSY exitKeyed ignored — key='{}' not open", key);
                return false;
            }
            exit(source);
            return true;
        }
    }

    /**
     * Hard reset to "not busy" — used by user-driven halt commands
     * (ESC / {@code /pause} / {@code /stop}) and by a session (re-)bind,
     * so the animation goes away immediately even though the underlying
     * chat round-trip may still be in flight on the WebSocket. Lingering
     * {@link #exit(String)} calls are absorbed by the {@code n > 0}
     * guard, lingering {@link #exitKeyed} calls by the key set.
     */
    public void clear() {
        synchronized (openKeys) {
            openKeys.clear();
            int prior = inFlight.getAndSet(0);
            if (prior > 0) {
                log.info("BUSY clear (prior depth={})", prior);
                fire(false);
            }
        }
    }

    /**
     * Notify edge listeners. Runs on the caller's thread; a throwing or
     * slow listener must never corrupt the counter, so every callback is
     * isolated in its own try/catch.
     */
    private void fire(boolean started) {
        for (BusyListener l : listeners) {
            try {
                if (started) {
                    l.onBusyStart();
                } else {
                    l.onBusyEnd();
                }
            } catch (RuntimeException e) {
                log.trace("BusyListener {} failed on {}", l.getClass().getSimpleName(),
                        started ? "start" : "end", e);
            }
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
