package de.mhus.vance.shared.redis;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/**
 * Live-feature primitives: {@link #publish}/{@link #subscribe} (Pub/Sub) and
 * {@link #hashPut}/{@link #hashGetAll} (ephemeral HASH state with TTL) and {@link #valuePut}/
 * {@link #valueGet} (ephemeral single values with TTL).
 *
 * <p>Two implementations, picked by what is on the classpath:
 * {@code RedisMessagingService} (cloud edition, module {@code vance-ee-redis}) spans pods through
 * Redis, {@link LocalMessagingService} serves the same contract inside this process. Callers do not tell
 * them apart, except through {@link #isEnabled()} where they have extra cross-pod work to do.
 *
 * <p>Topic convention:
 * <ul>
 *   <li><b>Tenant-scoped:</b> {@code vance:{tenant}:{channel}} — membership deltas,
 *       document-change events, NOTIFY etc.</li>
 *   <li><b>Global:</b> {@code vance:global:{channel}} — cluster lifecycle events, cross-tenant
 *       broadcasts</li>
 * </ul>
 *
 * <p>Subscribing is idempotent per topic: a repeated subscribe is a no-op. Unsubscribe removes
 * only the listener registered by the matching subscribe. A pattern subscribe
 * ({@code vance:*:documents:presence}) hands the handler the topic that actually matched, so the
 * consumer can parse the tenant out of it.
 *
 * <p>Delivery is asynchronous in both implementations: a handler never runs on the publisher's
 * thread.
 */
public interface VanceRedisMessagingService {

    String GLOBAL = "global";

    /**
     * {@code true} when messages reach other pods. {@code false} means this process is the only
     * node: events still arrive at local subscribers, but there is no peer to fan out to.
     */
    boolean isEnabled();

    // ─── tenant-scoped pub/sub ────────────────────────────────────────

    /** Publish a string-encoded message to {@code vance:{tenant}:{channel}}. */
    void publish(String tenantId, String channel, String message);

    /**
     * Subscribe to {@code vance:{tenant}:{channel}}.
     *
     * @param handler invoked with {@code (topic, payload)} on every incoming message
     */
    void subscribe(String tenantId, String channel, BiConsumer<String, String> handler);

    /** Remove the listener registered for {@code vance:{tenant}:{channel}}. */
    void unsubscribe(String tenantId, String channel);

    // ─── cross-tenant pattern pub/sub ─────────────────────────────────

    /**
     * Subscribe to a {@code channel} across all tenants via the {@code vance:*:{channel}} pattern.
     * The handler receives the matched topic so the caller can extract the tenant.
     */
    void subscribeAcrossTenants(String channel, BiConsumer<String, String> handler);

    void unsubscribeAcrossTenants(String channel);

    // ─── global pub/sub ───────────────────────────────────────────────

    /** Publish to a non-tenant-scoped channel ({@code vance:global:{channel}}). */
    void publishGlobal(String channel, String message);

    /** Subscribe to a non-tenant-scoped channel. */
    void subscribeGlobal(String channel, BiConsumer<String, String> handler);

    void unsubscribeGlobal(String channel);

    // ─── tenant-scoped HASH state with TTL ────────────────────────────
    //
    // Hash keys are tenant-prefixed exactly like pub/sub topics (`vance:{tenant}:{subKey}`). The
    // TTL is on the whole key and every hashPut refreshes it, so heartbeating any field keeps the
    // roster alive. When a writer stops, the key expires on its own.

    /** Set {@code field → value} on the hash at {@code subKey} and (re)set the key TTL. */
    void hashPut(String tenantId, String subKey, String field, String value, Duration ttl);

    /** All fields of the hash at {@code subKey}; empty when the key is missing or expired. */
    Map<String, String> hashGetAll(String tenantId, String subKey);

    /** Drop one field; an emptied hash disappears. */
    void hashDelete(String tenantId, String subKey, String field);

    /** Refresh the TTL of an existing hash key. */
    void hashRefreshTtl(String tenantId, String subKey, Duration ttl);

    // ─── tenant-scoped single values with TTL ─────────────────────────
    //
    // One string per key, expiring on its own: advisory locks, small caches. Same key naming as
    // the hashes (`vance:{tenant}:{subKey}`).

    /** Store {@code value} at {@code subKey}, replacing any current value, expiring after {@code ttl}. */
    void valuePut(String tenantId, String subKey, String value, Duration ttl);

    /**
     * Store {@code value} only if nothing live is at {@code subKey}. The check and the write are one
     * atomic step.
     *
     * @return {@code true} when this call stored the value
     */
    boolean valuePutIfAbsent(String tenantId, String subKey, String value, Duration ttl);

    /** The live value at {@code subKey}; empty when missing or expired. */
    Optional<String> valueGet(String tenantId, String subKey);

    /** Whether a live value is at {@code subKey}. */
    boolean valueExists(String tenantId, String subKey);

    /** Remove the value at {@code subKey}. */
    void valueDelete(String tenantId, String subKey);

    // ─── topic naming ─────────────────────────────────────────────────

    static String topicOf(String tenantId, String channel) {
        return "vance:" + tenantId + ":" + channel;
    }

    static String globalTopicOf(String channel) {
        return "vance:" + GLOBAL + ":" + channel;
    }

    /**
     * Extracts the tenant segment from a {@code vance:{tenant}:{channel}} topic — the form
     * delivered to {@link #subscribeAcrossTenants} handlers. The channel itself may contain colons
     * (e.g. {@code documents:presence}), so only the segment between the first two colons is the
     * tenant. Returns {@code ""} for a malformed topic.
     *
     * <p>Cross-pod fan-out handlers MUST scope delivery to this tenant: path strings collide
     * across tenants, so an unscoped local fan-out leaks presence/content across tenant
     * boundaries.
     */
    static String tenantFromTopic(@Nullable String topic) {
        if (topic == null) return "";
        int first = topic.indexOf(':');
        if (first < 0) return "";
        int second = topic.indexOf(':', first + 1);
        if (second < 0) return "";
        return topic.substring(first + 1, second);
    }
}
