package de.mhus.vance.foot.cli;

import de.mhus.vance.foot.ui.BusyListener;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * One-shot turn-completion gate for the {@code --skill} non-interactive
 * mode ({@link VanceFootCommand}). Implements {@link BusyListener} so it
 * rides the same {@code BusyIndicator} edge accounting that already
 * aggregates the synchronous chat round-trip <em>and</em> every async
 * {@code engine_turn_start}/{@code engine_turn_end} progress frame — no
 * separate turn-tracking to reimplement.
 *
 * <p>State-based (not edge-based) on purpose: {@link #arm()} installs a
 * fresh latch, {@link #onBusyStart()} records that a turn has begun, and
 * {@link #onBusyEnd()} releases the latch only once a start has been seen.
 * Because the latch holds the signal, a turn that starts <em>and</em>
 * finishes before the caller reaches {@link #awaitTurn} still releases it
 * — no lost wake-up.
 *
 * <p>Semantics are "first settle wins": the latch trips on the first
 * {@code busy → idle} edge after arming. A follow-up turn started by a
 * completion guard after that edge is not waited
 * for — for a one-shot the primary turn having produced its output is the
 * completion criterion.
 */
@Component
@Slf4j
public class OneShotTurnGate implements BusyListener {

    private volatile boolean armed = false;
    private volatile boolean sawStart = false;
    private final AtomicReference<@Nullable CountDownLatch> latch = new AtomicReference<>();

    /** Arm the gate for a single upcoming turn. Call before triggering the work. */
    public void arm() {
        sawStart = false;
        latch.set(new CountDownLatch(1));
        armed = true;
        log.trace("OneShotTurnGate armed");
    }

    @Override
    public void onBusyStart() {
        if (armed) {
            sawStart = true;
            log.trace("OneShotTurnGate saw turn start");
        }
    }

    @Override
    public void onBusyEnd() {
        if (armed && sawStart) {
            CountDownLatch l = latch.get();
            if (l != null) {
                l.countDown();
                log.trace("OneShotTurnGate settled");
            }
        }
    }

    /**
     * Blocks until an armed turn has started and settled, or the timeout
     * elapses. Returns {@code false} if the gate was never armed or the
     * timeout hit before the turn settled.
     */
    public boolean awaitTurn(Duration timeout) throws InterruptedException {
        CountDownLatch l = latch.get();
        if (l == null) {
            return false;
        }
        return l.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
