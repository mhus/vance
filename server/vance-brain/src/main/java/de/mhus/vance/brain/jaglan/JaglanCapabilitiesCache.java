package de.mhus.vance.brain.jaglan;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.toolpack.jaglan.JaglanCapabilities;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Caches what each mount says about itself, so a folder listing can describe
 * a mount without touching it.
 *
 * <p><b>Two reads, on purpose.</b> {@link #peek} never fetches and is what
 * {@code mounts()} uses — that call sits on the hot path of three listing
 * surfaces, and a project with five mounts of which three are dead would
 * otherwise pay three timeouts before the folder tree renders. {@link #warm}
 * fetches and is called from {@code stat} / {@code list}, where a remote call
 * is happening anyway. A cold cache therefore shows a mount with
 * {@code UNKNOWN} access and no item count, and the numbers appear on the next
 * listing.
 *
 * <p><b>The TTL here is ours, not the source's.</b> {@code JaglanCapabilities}
 * carries {@code metadataTtl}, which says how long a <i>directory listing or
 * file stat</i> stays valid — seconds to minutes. How long we trust a source's
 * <i>self-description</i> is a different question with a different answer
 * (minutes to hours), so it is a property here rather than a second field the
 * source gets to set.
 */
@Service
@Slf4j
public class JaglanCapabilitiesCache {

    /** The documented default, and what a hand-constructed instance uses. */
    static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    @Value("${vance.jaglan.capabilities-ttl-seconds:1800}")
    private long ttlSeconds = DEFAULT_TTL.toSeconds();

    /**
     * Rebuilt in {@link #applyConfiguredTtl()}: Caffeine bakes the expiry into
     * the instance, and a builder that runs in the field initialiser runs
     * before {@code @Value} injection. Built here as well so an instance
     * constructed by hand (unit tests) is usable without the Spring lifecycle.
     */
    private volatile Cache<String, JaglanCapabilities> cache = newCache(DEFAULT_TTL);

    /**
     * Remember a failed fetch briefly so a dead mount does not get probed on
     * every listing. Separate from the value cache because "we asked and it
     * broke" is not the same as "we never asked".
     */
    private final Cache<String, Boolean> failures = Caffeine.newBuilder()
            .maximumSize(512)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    /**
     * Apply the configured TTL once the property is injected.
     *
     * <p>Discarding the initial cache is free — nothing can have been fetched
     * before the context is up — and it is the only way the property can take
     * effect at all. Without it {@code vance.jaglan.capabilities-ttl-seconds}
     * was a documented knob wired to nothing: an operator setting it to 60 and
     * watching a source stay read-only for half an hour has no way to tell
     * that from a broken source.
     */
    @PostConstruct
    void applyConfiguredTtl() {
        Duration ttl = configuredTtl();
        if (ttl.isNegative()) {
            log.warn("vance.jaglan.capabilities-ttl-seconds is negative ({}s), using {}s",
                    ttlSeconds, DEFAULT_TTL.toSeconds());
            ttl = DEFAULT_TTL;
            ttlSeconds = DEFAULT_TTL.toSeconds();
        }
        cache = newCache(ttl);
        log.debug("Jaglan: capabilities are trusted for {}s", ttl.toSeconds());
    }

    private static Cache<String, JaglanCapabilities> newCache(Duration ttl) {
        return Caffeine.newBuilder()
                .maximumSize(512)
                .expireAfterWrite(ttl)
                .build();
    }

    /** Cached capabilities, or {@code null} — never fetches. */
    public @Nullable JaglanCapabilities peek(
            String tenantId, String projectId, String mount) {
        return cache.getIfPresent(key(tenantId, projectId, mount));
    }

    /**
     * {@code true} when the last attempt to fetch this mount's declaration
     * failed and is still remembered.
     *
     * <p>Exists so a caller can tell the two reasons for an absent
     * declaration apart: <b>asked and it broke</b> versus <b>never asked</b>.
     * They look identical to {@link #peek}, and conflating them makes a
     * perfectly healthy mount report an outage for the first minutes after a
     * restart — the same "unknown masquerading as bad" mistake this whole
     * subsystem keeps having to avoid.
     */
    public boolean failedRecently(String tenantId, String projectId, String mount) {
        return Boolean.TRUE.equals(failures.getIfPresent(key(tenantId, projectId, mount)));
    }

    /**
     * Cached capabilities, fetching on a miss.
     *
     * @return the capabilities, or {@code null} when the source could not be
     *         asked — callers treat that as "unknown", not as an error
     */
    public @Nullable JaglanCapabilities warm(
            String tenantId, String projectId, JaglanInstance instance) {
        String key = key(tenantId, projectId, instance.mount());
        JaglanCapabilities cached = cache.getIfPresent(key);
        if (cached != null) return cached;
        if (Boolean.TRUE.equals(failures.getIfPresent(key))) return null;
        try {
            JaglanCapabilities caps = instance.capabilities();
            if (caps != null) {
                cache.put(key, caps);
                return caps;
            }
            return null;
        } catch (RuntimeException e) {
            failures.put(key, Boolean.TRUE);
            log.warn("Jaglan: mount '{}' in {}/{} failed to report capabilities: {}",
                    instance.mount(), tenantId, projectId, e.toString());
            return null;
        }
    }

    /** Forget a mount's declaration — part of the explicit refresh. */
    public void evict(String tenantId, String projectId, String mount) {
        String key = key(tenantId, projectId, mount);
        cache.invalidate(key);
        failures.invalidate(key);
    }

    /** How long a source's self-description is trusted. */
    public Duration configuredTtl() {
        return Duration.ofSeconds(ttlSeconds);
    }

    private static String key(String tenantId, String projectId, String mount) {
        // Project-scoped like the source factory: the mount name is a local
        // label ("library", "archive"), so two projects naming a mount after
        // its job are two different sources under one name.
        return tenantId + '|' + projectId + '|' + mount;
    }
}
