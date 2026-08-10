package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.thinkprocess.ProcessCountsNotification;
import org.junit.jupiter.api.Test;

/**
 * The session-scoping contract that replaces explicit reset plumbing: counts
 * only answer for the session they were pushed for, so switching sessions
 * cannot leave a stale number in the status bar.
 */
class ProcessCountsStateTest {

    @Test
    void countsFor_returnsFrameOfMatchingSession() {
        ProcessCountsState state = new ProcessCountsState();
        state.apply(counts("s-1", 1, 2, 0));

        ProcessCountsNotification result = state.countsFor("s-1");

        assertThat(result).isNotNull();
        assertThat(result.getRunning()).isEqualTo(1);
        assertThat(result.getTotal()).isEqualTo(3);
    }

    @Test
    void countsFor_ignoresFrameOfAnotherSession() {
        ProcessCountsState state = new ProcessCountsState();
        state.apply(counts("s-1", 1, 2, 0));

        assertThat(state.countsFor("s-2")).isNull();
    }

    @Test
    void countsFor_hidesEmptyTally() {
        ProcessCountsState state = new ProcessCountsState();
        state.apply(counts("s-1", 0, 0, 0));

        assertThat(state.countsFor("s-1")).isNull();
    }

    @Test
    void countsFor_beforeFirstPush_isNull() {
        assertThat(new ProcessCountsState().countsFor("s-1")).isNull();
    }

    @Test
    void countsFor_withoutBoundSession_isNull() {
        ProcessCountsState state = new ProcessCountsState();
        state.apply(counts("s-1", 1, 0, 0));

        assertThat(state.countsFor(null)).isNull();
    }

    private static ProcessCountsNotification counts(
            String sessionId, int running, int waiting, int blocked) {
        return ProcessCountsNotification.builder()
                .sessionId(sessionId)
                .running(running)
                .waiting(waiting)
                .blocked(blocked)
                .total(running + waiting + blocked)
                .build();
    }
}
