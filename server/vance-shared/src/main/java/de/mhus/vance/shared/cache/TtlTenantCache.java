package de.mhus.vance.shared.cache;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * A short-lived, scoped cache with a single per-entry TTL. The shared shape
 * that {@link de.mhus.vance.shared.settings.RetentionSettingCache} and the
 * settings cache of {@code LlmBudgetService} both need; the call path reads
 * a value, the loader runs at most once per TTL, and {@link #invalidate(Object)}
 * drops the entry for tests or an explicit admin action.
 *
 * <h2>Why this exists as a reusable building block</h2>
 *
 * The settings cascade is up to three uncached Mongo reads per lookup, and
 * retention plus budget limits are read on the write path twice per model-call
 * attempt. Both subsystems arrived at the same {@code ConcurrentHashMap} +
 * {@code nanoTime} expiry pattern independently, and a third or fourth copy
 * would just keep drifting. This class is the one place that pattern lives.
 *
 * <h2>What is deliberately not here</h2>
 *
 * Not a general settings cache. {@link #get(Object, Loader)} trusts the
 * loader's return value as-is, including a {@code null} meaning "no value" —
 * a {@code null} value is cached as an entry, not treated as a miss, so a
 * caller that distinguishes "absent" from "not looked up" cannot rely on
 * cache behaviour to express that. The cache is correct only for callers
 * where a TTL of staleness is acceptable.
 *
 * <p>No cross-pod invalidation. A minute of staleness is acceptable for
 * retention and for budget limits; an exact write-path broadcast would be
 * over-engineering for those.
 *
 * @param <K> opaque key type — the caller builds it, including any separator
 *            that keeps distinct scopes from colliding
 * @param <V> value type
 */
public class TtlTenantCache<K, V> {

    /** How long an entry is trusted. Configurable per instance. */
    private final Duration ttl;

    /**
     * Bound on distinct entries, so a tenant with very many scopes cannot
     * turn this into a leak. On overflow the map is cleared rather than
     * evicted one by one: the entries are cheap to rebuild, and an
     * approximate LRU here would be more machinery than the problem deserves.
     * Same default as {@code RetentionSettingCache.MAX_ENTRIES}.
     */
    static final int MAX_ENTRIES = 10_000;

    private final Map<K, Entry<V>> cache = new ConcurrentHashMap<>();

    public TtlTenantCache(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * The cached value for {@code key}, or {@code loader.load()} on miss.
     * A {@code null} loader result is cached as an entry — the caller's
     * loader decides what "no value" means.
     */
    public @Nullable V get(K key, Loader<V> loader) {
        long now = System.nanoTime();
        Entry<V> hit = cache.get(key);
        if (hit != null && now < hit.expiresAtNanos()) {
            return hit.value();
        }
        V loaded = loader.load();
        if (cache.size() >= MAX_ENTRIES && !cache.containsKey(key)) {
            cache.clear();
        }
        cache.put(key, new Entry<>(loaded, now + ttl.toNanos()));
        return loaded;
    }

    /** Drops the entry for {@code key}, if any. */
    public void invalidate(K key) {
        cache.remove(key);
    }

    /** Drops every entry — for tests and for an explicit reload action. */
    public void invalidateAll() {
        cache.clear();
    }

    /**
     * Loads a value on cache miss. Pure function of the world outside the
     * cache, kept narrow so the {@link TtlTenantCache} itself has no I/O.
     */
    @FunctionalInterface
    public interface Loader<V> {
        @Nullable
        V load();
    }

    /** {@code expiresAtNanos} is on {@link System#nanoTime()}, which never jumps. */
    private record Entry<V>(@Nullable V value, long expiresAtNanos) {}
}
