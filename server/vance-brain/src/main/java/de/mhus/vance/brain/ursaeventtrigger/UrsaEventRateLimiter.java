package de.mhus.vance.brain.ursaeventtrigger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * A token bucket per event: {@code perMinute} calls may come in a burst, and the bucket refills at that rate.
 *
 * <p>The event endpoint carries no JWT, so without this nothing bounds how fast a caller may start runs. It is
 * <b>per pod</b> — a deployment with several pods lets through up to that many times the limit, and a limit
 * that must hold across pods belongs in the gateway in front. {@code 0} switches the limit off.
 */
@Component
class UrsaEventRateLimiter {

    /**
     * Idle buckets are dropped: a bucket untouched for five minutes has refilled to full anyway, so forgetting it
     * loses nothing, and the map cannot grow with every event that was ever called. Bounded in size as well.
     */
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .maximumSize(10_000)
            .build();

    private final LongSupplier nanoClock;

    UrsaEventRateLimiter() {
        this(System::nanoTime);
    }

    UrsaEventRateLimiter(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    private static final class Bucket {
        double tokens;
        long lastNanos;
    }

    /**
     * Take one call from the bucket of {@code key}.
     *
     * @return empty when the call may go on, otherwise how long until one is free
     */
    Optional<Duration> refusal(String key, int perMinute) {
        if (perMinute <= 0) return Optional.empty();
        Bucket bucket = buckets.get(key, k -> {
            Bucket b = new Bucket();
            b.tokens = perMinute;
            b.lastNanos = nanoClock.getAsLong();
            return b;
        });
        synchronized (bucket) {
            long now = nanoClock.getAsLong();
            double refill = (now - bucket.lastNanos) * (perMinute / 60.0) / 1_000_000_000.0;
            bucket.tokens = Math.min(perMinute, bucket.tokens + refill);
            bucket.lastNanos = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return Optional.empty();
            }
            double seconds = (1.0 - bucket.tokens) * 60.0 / perMinute;
            return Optional.of(Duration.ofSeconds((long) Math.ceil(seconds)));
        }
    }
}
