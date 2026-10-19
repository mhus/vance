package de.mhus.vance.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The cron-driven tenant cache. What matters: a hit returns immediately, a
 * miss loads once, the write path always reloads with a fresh ticket, the
 * tick reloads every entry but defers to a newer write, idle eviction drops
 * cold entries, and a tick failure keeps the last known state.
 */
class CronTenantCacheTest {

    private static final String KEY = "acme";

    @Test
    void getOrLoad_hitServesFromRam_neverCallsTheLoader() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T10:00:00Z"));
        AtomicInteger calls = new AtomicInteger();
        CronTenantCache<String, String> cache =
                new CronTenantCache<>("0 * * * * *", Duration.ofMinutes(1), clock, k -> {
                    calls.incrementAndGet();
                    return "v-" + k;
                });

        assertThat(cache.getOrLoad(KEY)).isEqualTo("v-acme");
        assertThat(cache.getOrLoad(KEY)).isEqualTo("v-acme");
        assertThat(cache.getOrLoad(KEY)).isEqualTo("v-acme");

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void getOrLoad_throwingLoader_propagates_andLeavesNothingCached() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T10:00:00Z"));
        AtomicInteger calls = new AtomicInteger();
        CronTenantCache<String, String> cache =
                new CronTenantCache<>("0 * * * * *", Duration.ofMinutes(1), clock, k -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("mongo down");
                });

        try {
            cache.getOrLoad(KEY);
        } catch (RuntimeException expected) {
            // ignore — the contract is that getOrLoad propagates
        }

        assertThat(cache.peek(KEY)).isNull();
        assertThat(calls.get()).isEqualTo(1);

        // The next call retries — nothing was cached.
        try {
            cache.getOrLoad(KEY);
        } catch (RuntimeException expected) {
            // ignore
        }
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void reload_alwaysCallsTheLoader_andReplacesTheEntry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T10:00:00Z"));
        AtomicReference<String> store = new AtomicReference<>("v1");
        CronTenantCache<String, String> cache =
                new CronTenantCache<>("0 * * * * *", Duration.ofMinutes(1), clock, k -> store.get());

        assertThat(cache.getOrLoad(KEY)).isEqualTo("v1");

        store.set("v2");
        assertThat(cache.reload(KEY)).isEqualTo("v2");
        assertThat(cache.peek(KEY)).isEqualTo("v2");
    }

    @Test
    void tick_picksUpAChangeFromAnotherPod() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T10:00:00Z"));
        AtomicReference<String> store = new AtomicReference<>("v1");
        CronTenantCache<String, String> cache =
                new CronTenantCache<>("0 * * * * *", Duration.ofMinutes(1), clock, k -> store.get());

        cache.getOrLoad(KEY);
        store.set("v2");

        cache.tick();

        assertThat(cache.peek(KEY)).isEqualTo("v2");
    }

    @Test
    void tick_failure_keepsTheLastState() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T10:00:00Z"));
        AtomicBoolean throwNext = new AtomicBoolean(false);
        CronTenantCache<String, String> cache =
                new CronTenantCache<>("0 * * * * *", Duration.ofMinutes(1), clock, k -> {
                    if (throwNext.get()) {
                        throw new IllegalStateException("mongo down");
                    }
                    return "v";
                });

        cache.getOrLoad(KEY); // v cached
        throwNext.set(true);

        cache.tick();

        // Tick loader threw — the cache still holds "v", not removed.
        assertThat(cache.peek(KEY)).isEqualTo("v");
    }

    @Test
    void tick_dropsEntriesNotAskedForAWhile() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T10:00:00Z"));
        AtomicInteger calls = new AtomicInteger();
        CronTenantCache<String, String> cache =
                new CronTenantCache<>("0 * * * * *", Duration.ofMinutes(1), clock, k -> {
                    calls.incrementAndGet();
                    return "v";
                });

        cache.getOrLoad(KEY);
        clock.now = clock.now.plus(Duration.ofMinutes(1)).plusSeconds(1);

        cache.tick();

        assertThat(cache.peek(KEY)).isNull();
    }

    @Test
    void tick_doesNotResurrectAnEvictedEntry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T10:00:00Z"));
        AtomicInteger calls = new AtomicInteger();
        CronTenantCache<String, String> cache =
                new CronTenantCache<>("0 * * * * *", Duration.ofMinutes(1), clock, k -> {
                    calls.incrementAndGet();
                    return "v";
                });

        cache.getOrLoad(KEY);
        clock.now = clock.now.plus(Duration.ofMinutes(1)).plusSeconds(1);

        cache.tick();
        cache.tick();

        assertThat(cache.peek(KEY)).isNull();
        // The initial getOrLoad counted as one call. The two ticks both evicted
        // before loading, so the loader was never called again.
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void nextRefreshAt_andVerifyAt_useWallClockCron() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T10:00:17Z"));
        CronTenantCache<String, String> cache =
                new CronTenantCache<>("0 * * * * *", Duration.ofMinutes(1), clock, k -> "v");

        assertThat(cache.nextRefreshAt()).isEqualTo(Instant.parse("2026-10-17T10:01:00Z"));
        assertThat(cache.verifyAt()).isEqualTo(Instant.parse("2026-10-17T10:01:02Z"));
    }

    @Test
    void startAndStop_isIdempotent() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T10:00:00Z"));
        CronTenantCache<String, String> cache =
                new CronTenantCache<>("0 0 0 1 1 *", Duration.ofMinutes(1), clock, k -> "v");

        cache.start();
        cache.stop();
        cache.stop();
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
