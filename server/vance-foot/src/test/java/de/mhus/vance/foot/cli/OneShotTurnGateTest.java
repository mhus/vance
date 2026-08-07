package de.mhus.vance.foot.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The completion gate behind {@code --skill} non-interactive mode. It
 * decides when a one-shot run may exit, so the interesting cases are the
 * ones where a naive edge-triggered wait would either hang forever or
 * return before the turn produced anything.
 */
class OneShotTurnGateTest {

    private static final Duration INSTANT = Duration.ofMillis(50);

    private OneShotTurnGate gate;

    @BeforeEach
    void setUp() {
        gate = new OneShotTurnGate();
    }

    @Test
    void turnThatSettledBeforeTheWait_stillReleases() throws Exception {
        // The lost-wake-up case: for a fast turn the whole
        // start→end pair can happen before the caller ever reaches
        // awaitTurn. The latch has to carry the signal.
        gate.arm();
        gate.onBusyStart();
        gate.onBusyEnd();

        assertThat(gate.awaitTurn(INSTANT)).isTrue();
    }

    @Test
    void turnSettlingWhileWaiting_releases() throws Exception {
        gate.arm();
        Thread worker = new Thread(() -> {
            gate.onBusyStart();
            gate.onBusyEnd();
        });
        worker.start();

        assertThat(gate.awaitTurn(Duration.ofSeconds(5))).isTrue();
        worker.join();
    }

    @Test
    void neverArmed_returnsFalseWithoutBlocking() throws Exception {
        assertThat(gate.awaitTurn(INSTANT)).isFalse();
    }

    @Test
    void idleWithoutAStart_doesNotRelease() throws Exception {
        // A stray busy→idle edge from something that is not our turn
        // must not be read as "the turn is done".
        gate.arm();
        gate.onBusyEnd();

        assertThat(gate.awaitTurn(INSTANT)).isFalse();
    }

    @Test
    void turnStartedButNotSettled_timesOut() throws Exception {
        gate.arm();
        gate.onBusyStart();

        assertThat(gate.awaitTurn(INSTANT)).isFalse();
    }

    @Test
    void busyEdgesBeforeArming_areIgnored() throws Exception {
        gate.onBusyStart();
        gate.onBusyEnd();
        gate.arm();

        assertThat(gate.awaitTurn(INSTANT)).isFalse();
    }

    @Test
    void rearming_discardsTheSettledStateOfThePreviousTurn() throws Exception {
        gate.arm();
        gate.onBusyStart();
        gate.onBusyEnd();
        assertThat(gate.awaitTurn(INSTANT)).isTrue();

        gate.arm();

        assertThat(gate.awaitTurn(INSTANT))
                .as("a fresh arm must wait for a fresh turn")
                .isFalse();
    }

    @Test
    void followUpTurnAfterTheFirstSettle_isNotWaitedFor() throws Exception {
        // "First settle wins": a completion guard or post-completion hook
        // may start another turn afterwards, but the one-shot's output
        // criterion is the primary turn.
        gate.arm();
        gate.onBusyStart();
        gate.onBusyEnd();
        gate.onBusyStart();

        assertThat(gate.awaitTurn(INSTANT)).isTrue();
    }
}
