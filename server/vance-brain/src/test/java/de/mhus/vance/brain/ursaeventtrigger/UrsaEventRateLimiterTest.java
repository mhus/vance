package de.mhus.vance.brain.ursaeventtrigger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class UrsaEventRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final UrsaEventRateLimiter limiter = new UrsaEventRateLimiter(nanos::get);

    private static long seconds(long s) {
        return Duration.ofSeconds(s).toNanos();
    }

    @Test
    void aBurstUpToTheLimitPassesAndThenTheNextCallWaits() {
        for (int i = 0; i < 3; i++) assertThat(limiter.refusal("e", 3)).isEmpty();

        assertThat(limiter.refusal("e", 3))
                .hasValueSatisfying(wait -> assertThat(wait.toSeconds()).isBetween(1L, 60L));
    }

    @Test
    void theBucketRefillsAtTheRateOfTheLimit() {
        for (int i = 0; i < 6; i++) limiter.refusal("e", 6);
        assertThat(limiter.refusal("e", 6)).isPresent();

        nanos.addAndGet(seconds(10)); // 6 per minute: one every ten seconds

        assertThat(limiter.refusal("e", 6)).isEmpty();
        assertThat(limiter.refusal("e", 6)).isPresent();
    }

    @Test
    void eventsHaveBucketsOfTheirOwn() {
        limiter.refusal("a", 1);

        assertThat(limiter.refusal("a", 1)).isPresent();
        assertThat(limiter.refusal("b", 1)).isEmpty();
    }

    @Test
    void zeroSwitchesTheLimitOff() {
        for (int i = 0; i < 1000; i++) assertThat(limiter.refusal("e", 0)).isEmpty();
    }
}
