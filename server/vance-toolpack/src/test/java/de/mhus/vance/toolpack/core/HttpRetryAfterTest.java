package de.mhus.vance.toolpack.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class HttpRetryAfterTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");

    @Test
    void secondsAreAddedToNow() {
        assertThat(HttpRetryAfter.parse("30", NOW)).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void anHttpDateIsTakenAsIs() {
        assertThat(HttpRetryAfter.parse("Fri, 06 Nov 2026 10:05:00 GMT", NOW))
                .isEqualTo(Instant.parse("2026-11-06T10:05:00Z"));
    }

    @Test
    void aNumberBeyondWhatAnInstantHoldsIsCapped_notAnException() {
        Instant moment = HttpRetryAfter.parse("99999999999999999", NOW);

        assertThat(moment).isNotNull().isAfter(NOW);
        assertThat(HttpRetryAfter.parse(String.valueOf(Long.MAX_VALUE), NOW)).isNotNull();
        assertThat(HttpRetryAfter.parse(String.valueOf(Long.MIN_VALUE), NOW)).isNotNull();
    }

    @Test
    void absentOrUnreadableCarriesNoInstruction() {
        assertThat(HttpRetryAfter.parse(null, NOW)).isNull();
        assertThat(HttpRetryAfter.parse(" ", NOW)).isNull();
        assertThat(HttpRetryAfter.parse("soon", NOW)).isNull();
    }
}
