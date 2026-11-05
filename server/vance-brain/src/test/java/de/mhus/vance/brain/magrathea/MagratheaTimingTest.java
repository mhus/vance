package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MagratheaTimingTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:10Z");

    @Test
    void measuresFromTheClaim() {
        assertThat(MagratheaTiming.between(Instant.parse("2026-11-05T10:00:00Z"), NOW))
                .isEqualTo(10_000L);
    }

    @Test
    void aTaskThatWasNeverClaimedHasNoDuration() {
        assertThat(MagratheaTiming.between(null, NOW)).isZero();
        assertThat(MagratheaTiming.sinceClaimMs(new MagratheaTaskDocument())).isZero();
    }

    @Test
    void neverGoesNegativeWhenClocksDisagree() {
        assertThat(MagratheaTiming.between(Instant.parse("2026-11-05T10:00:20Z"), NOW))
                .isZero();
    }

    @Test
    void readsTheClaimOffTheTaskRow() {
        MagratheaTaskDocument task = new MagratheaTaskDocument();
        task.setClaimedAt(Instant.now().minusSeconds(5));

        assertThat(MagratheaTiming.sinceClaimMs(task)).isBetween(5_000L, 30_000L);
    }
}
