package de.mhus.vance.brain.wonko;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class WonkoConvergenceTest {

    private static WonkoState.RoundStat stat(int asked, int later, int openAfter) {
        WonkoState.RoundStat s = new WonkoState.RoundStat();
        s.setAsked(asked);
        s.setLater(later);
        s.setOpenAfter(openAfter);
        return s;
    }

    @Test
    void maxRoundsReached_stops() {
        assertThat(WonkoConvergence.stopReason(List.of(), 6, 6)).isEqualTo(WonkoConvergence.MAX_ROUNDS);
    }

    @Test
    void twoRoundsMostlyLater_isFatigue() {
        assertThat(WonkoConvergence.stopReason(List.of(stat(3, 2, 5), stat(3, 3, 4)), 2, 6))
                .isEqualTo(WonkoConvergence.FATIGUE);
    }

    @Test
    void openTopicsNotShrinkingForTwoRounds_isNoProgress() {
        assertThat(WonkoConvergence.stopReason(List.of(stat(3, 0, 4), stat(3, 0, 4), stat(3, 0, 5)), 3, 6))
                .isEqualTo(WonkoConvergence.NO_PROGRESS);
    }

    @Test
    void shrinkingOpenTopics_goesOn() {
        assertThat(WonkoConvergence.stopReason(List.of(stat(3, 0, 6), stat(3, 1, 4), stat(2, 0, 2)), 3, 6))
                .isNull();
    }
}
