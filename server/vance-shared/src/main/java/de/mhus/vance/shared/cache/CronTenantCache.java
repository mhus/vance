package de.mhus.vance.shared.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

/**
 * A scoped cache with a wall-clock cron refresh, idle eviction, and an
 * immediate reload path. Built for the LLM power switch ({@code
 * LlmPowerSwitch}) — anything where a tenant's state needs to be cheap on
 * the call path, current across pods without an event bus, and recoverable
 * from a Mongo hiccup.
 *
 * <h2>Why this exists</h2>
 *
 * A TTL cache ({@link TtlTenantCache}) works when the value either changes
 * often enough that one stale read is harmless or rarely enough that one
 * stale read is acceptable. The LLM power switch is somewhere in between:
 * an admin flipping the switch wants to see it take effect within a minute,
 * but a chat turn should not pay a Mongo read on every call. The cron +
 * immediate-reload pattern covers both — the write path reloads the
 * affected key at once, and the cron tick keeps the other pods in step.
 *
 * <h2>Why a dedicated single-thread scheduler</h2>
 *
 * The spec is explicit: the tick must run on its own thread in UTC, not on
 * the shared Spring scheduler, so a busy neighbor job cannot delay it past
 * the second-00 boundary the admin view uses to know when to look.
 *
 * <h2>The ticket race</h2>
 *
 * Reloads and tick reads share the same key. A tick captures a ticket,
 * reads from Mongo, and only installs the result if its ticket is still the
 * latest when it returns — a write-path reload that completed in the
 * meantime wins, because the admin's action is newer than the tick's
 * background poll.
 *
 * <h2>Failure</h2>
 *
 * A failed first load throws; the caller decides whether to admit the call
 * without caching (the power switch's "Mongo down → admit" rule). A failed
 * tick reload keeps the last known state. A failed stop is a no-op.
 *
 * @param <K> opaque key type — the caller builds it
 * @param <V> value type
 */
@Slf4j
public class CronTenantCache<K, V> {

    /**
     * Grace period added to {@link #nextRefreshAt()} for the admin view:
     * the tick itself needs a moment to read the store, and checking at
     * the tick would race it.
     */
    public static final Duration VERIFY_GRACE = Duration.ofSeconds(2);

    /** Bound on entries; on overflow the map is cleared, entries are cheap to rebuild. */
    static final int MAX_ENTRIES = 10_000;

    private final Function<K, V> loader;
    private final String cron;
    private final CronExpression refreshCron;
    private final Duration idleEviction;
    private final Clock clock;
    private final Map<K, Entry<V>> cache = new ConcurrentHashMap<>();
    private final AtomicLong tickets = new AtomicLong();

    private @Nullable ThreadPoolTaskScheduler scheduler;

    public CronTenantCache(String cron, Duration idleEviction, Clock clock, Function<K, V> loader) {
        this.cron = cron;
        this.refreshCron = CronExpression.parse(cron);
        this.idleEviction = idleEviction;
        this.clock = clock;
        this.loader = loader;
    }

    /**
     * The cached value for {@code key}, or the loader's result on miss.
     * A throwing loader leaves the cache untouched and propagates — the
     * caller decides whether to admit the call without caching.
     */
    public V getOrLoad(K key) {
        Instant now = clock.instant();
        Entry<V> hit = cache.get(key);
        if (hit != null) {
            hit.lastAsked = now;
            return hit.value;
        }
        return loadAndRemember(key, now);
    }

    /**
     * Bypass the cache check: always load, always remember with a new
     * ticket. This is the write path — a setting change is current from
     * the moment it returns.
     */
    public V reload(K key) {
        return loadAndRemember(key, clock.instant());
    }

    private V loadAndRemember(K key, Instant now) {
        long ticket = tickets.incrementAndGet();
        V loaded = loader.apply(key);
        return remember(key, loaded, ticket, now);
    }

    private V remember(K key, V value, long ticket, Instant now) {
        if (cache.size() >= MAX_ENTRIES && !cache.containsKey(key)) {
            cache.clear();
        }
        Entry<V> result = cache.compute(key, (k, current) -> {
            if (current == null) {
                return new Entry<>(value, ticket, now);
            }
            if (current.ticket > ticket) {
                current.lastAsked = now;
                return current;
            }
            return new Entry<>(value, ticket, now);
        });
        return result.value;
    }

    /** What the cache currently holds for {@code key}, or {@code null} when it holds nothing. */
    public @Nullable V peek(K key) {
        Entry<V> hit = cache.get(key);
        return hit == null ? null : hit.value;
    }

    /**
     * Reload every entry that has been asked for recently, drop the rest.
     * A loader failure keeps the last known state — a Mongo hiccup must
     * neither evict a tenant nor replace its state with a default.
     */
    public void tick() {
        Instant evictBefore = clock.instant().minus(idleEviction);
        for (Map.Entry<K, Entry<V>> e : cache.entrySet()) {
            Entry<V> entry = e.getValue();
            if (entry.lastAsked.isBefore(evictBefore)) {
                cache.remove(e.getKey(), entry);
                continue;
            }
            long ticket = tickets.incrementAndGet();
            V loaded;
            try {
                loaded = loader.apply(e.getKey());
            } catch (RuntimeException ex) {
                log.warn("CronTenantCache tick reload failed for key, keeping last state: {}", ex.toString());
                continue;
            }
            // Install only if no newer write happened while we were on the wire.
            cache.computeIfPresent(
                    e.getKey(),
                    (key, current) ->
                            current.ticket > ticket ? current : new Entry<>(loaded, ticket, current.lastAsked));
        }
    }

    /** The next refresh tick on the wall clock. */
    public Instant nextRefreshAt() {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        ZonedDateTime next = refreshCron.next(now);
        return next == null ? now.toInstant() : next.toInstant();
    }

    /** When the admin view should check that a change has reached every pod. */
    public Instant verifyAt() {
        return nextRefreshAt().plus(VERIFY_GRACE);
    }

    /** The clock the cache reads, exposed for callers that schedule UI checks relative to it. */
    public Instant now() {
        return clock.instant();
    }

    /** Start the refresh tick on its own thread, in UTC. Idempotent. */
    public void start() {
        if (scheduler != null) {
            return;
        }
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(1);
        s.setThreadNamePrefix("cron-tenant-cache-");
        s.setDaemon(true);
        s.initialize();
        s.schedule(this::tick, new CronTrigger(cron, ZoneOffset.UTC));
        this.scheduler = s;
    }

    /** Stop the refresh tick. Idempotent. */
    public void stop() {
        ThreadPoolTaskScheduler s = scheduler;
        if (s != null) {
            s.shutdown();
            scheduler = null;
        }
    }

    private static final class Entry<V> {
        final V value;
        final long ticket;
        volatile Instant lastAsked;

        Entry(V value, long ticket, Instant lastAsked) {
            this.value = value;
            this.ticket = ticket;
            this.lastAsked = lastAsked;
        }
    }
}
