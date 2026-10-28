package de.mhus.vance.runner.protocol;

import de.mhus.vance.runner.protocol.RunnerProtocolException.Kind;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers the {@code jti}s of tokens already used, until they would have expired
 * anyway, so a captured request cannot be sent twice. Fails closed: when the cache is
 * full of still-valid entries it refuses new tokens instead of forgetting old ones.
 */
public final class JtiCache {

    private final int maxEntries;
    private final Map<String, Instant> seen = new HashMap<>();

    public JtiCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    /**
     * Records {@code jti} until {@code expiresAt}. Returns {@code false} when it was
     * already used and is still valid.
     *
     * @throws RunnerProtocolException {@link Kind#TOKEN} when the cache is full of valid entries
     */
    public synchronized boolean firstUse(String jti, Instant expiresAt, Instant now) {
        Instant known = seen.get(jti);
        if (known != null && known.isAfter(now)) {
            return false;
        }
        if (seen.size() >= maxEntries) {
            seen.values().removeIf(expiry -> !expiry.isAfter(now));
            if (seen.size() >= maxEntries) {
                throw new RunnerProtocolException(Kind.TOKEN, "The replay cache is full");
            }
        }
        seen.put(jti, expiresAt);
        return true;
    }

    /** Number of remembered entries, expired ones included until purged. */
    public synchronized int size() {
        return seen.size();
    }
}
