package de.mhus.vance.brain.ursaeventtrigger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Remembers which deliveries a provider has made, so one it sends again — providers retry on a timeout or a
 * non-2xx — is answered with the first answer instead of starting the run a second time.
 *
 * <p>The key is the delivery id the provider puts in the header the event names ({@code idempotencyHeader}).
 * <b>Per pod</b>, and only as long as it is kept ({@code vance.events.idempotency-ttl}, ten minutes by default):
 * a retry that comes later, or lands on another pod, runs again. That covers the common retry — seconds after
 * the first try — and says so rather than promising exactly-once.
 */
@Component
class UrsaEventDeliveryCache {

    /** What a delivery id stands for. */
    static final class Delivery {
        private volatile UrsaEventService.@Nullable UrsaEventTriggerResult result;

        UrsaEventService.@Nullable UrsaEventTriggerResult result() {
            return result;
        }
    }

    /** The outcome of {@link #claim}. */
    record Claim(boolean first, Delivery delivery) {

        /** The delivery is being handled right now, by an earlier request. */
        boolean inFlight() {
            return !first && delivery.result() == null;
        }
    }

    /** How many delivery ids are kept at most. */
    static final int MAX_ENTRIES = 10_000;

    private final Cache<String, Delivery> deliveries;

    /** How many ids are held now — for tests and diagnostics. */
    long size() {
        deliveries.cleanUp();
        return deliveries.estimatedSize();
    }

    UrsaEventDeliveryCache(@Value("${vance.events.idempotency-ttl:PT10M}") Duration ttl) {
        // Three bounds, none of which a caller controls: a time to live, a number of entries, and soft values — under
        // memory pressure the JVM may drop entries before either bound is reached. A flood of new ids can push old
        // ones out (an old delivery sent again then runs again), but it cannot fill the heap.
        this.deliveries = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(MAX_ENTRIES)
                .softValues()
                .build();
    }

    /** Register {@code key} if it is new; otherwise say it was seen. */
    Claim claim(String key) {
        Delivery fresh = new Delivery();
        Delivery existing = deliveries.asMap().putIfAbsent(key, fresh);
        return existing == null ? new Claim(true, fresh) : new Claim(false, existing);
    }

    /** The first request answered: later ones with the same id get this answer. */
    void complete(Claim claim, UrsaEventService.UrsaEventTriggerResult result) {
        claim.delivery().result = result;
    }

    /** The first request failed: forget the id, so the provider's retry is a fresh try. */
    void release(String key) {
        deliveries.invalidate(key);
    }
}
