package de.mhus.vance.shared.redis;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

/**
 * Cross-pod live-feature primitives on top of Spring's Redis client —
 * the only place that talks to Redis directly. Two concerns under one
 * roof: {@link #publish}/{@link #subscribe} (Pub/Sub) and
 * {@link #hashPut}/{@link #hashGetAll} (ephemeral HASH state with TTL).
 *
 * <p>Topic-Konvention:
 * <ul>
 *   <li><b>Tenant-scoped:</b> {@code vance:{tenant}:{channel}} —
 *       Membership-Deltas, Document-Change-Events, NOTIFY etc.</li>
 *   <li><b>Global:</b> {@code vance:global:{channel}} — Cluster-Lifecycle
 *       Events, Cross-Tenant-Broadcasts</li>
 * </ul>
 *
 * <p>Subscribe ist Listener-deduplizierend pro Topic — wiederholtes
 * Subscribe ist Idempotent (no-op). Unsubscribe entfernt nur den vorher
 * dieselbe Methode registrierten Listener — andere Subscriber bleiben
 * intakt.
 *
 * <p>Pattern-Subscribe (z.B. {@code vance:*:documents:presence}) gibt
 * dem Handler das tatsächlich getroffene Topic als ersten Parameter,
 * sodass der Consumer den Tenant aus dem Topic-Pfad rausparsen kann.
 *
 * <p>Ported from nimbus-wb's {@code WorldRedisMessagingService}, gleiche
 * API-Form, anderer Topic-Prefix.
 */
@Service
@Slf4j
public class VanceRedisMessagingService {

    public static final String GLOBAL = "global";

    /**
     * {@code null} when {@code vance.redis.enabled=false} — the Beans are
     * conditional in {@link VanceRedisConfig}, so a disabled deployment
     * gives us {@code null} here and {@link #enabled} stays {@code false}.
     * All public methods become no-ops in that state.
     */
    private final @Nullable StringRedisTemplate redis;
    private final @Nullable RedisMessageListenerContainer container;
    private final boolean enabled;
    private final Map<String, MessageListener> listeners = new ConcurrentHashMap<>();

    public VanceRedisMessagingService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<RedisMessageListenerContainer> containerProvider) {
        this.redis = redisProvider.getIfAvailable();
        this.container = containerProvider.getIfAvailable();
        this.enabled = redis != null && container != null;
        if (!enabled) {
            log.info("VanceRedisMessagingService: disabled "
                    + "(vance.redis.enabled=false) — cross-pod pub/sub is a no-op. "
                    + "Live features work pod-local; multi-pod fan-out is not available.");
        }
    }

    /** {@code true} when Redis is configured and the Beans are wired. */
    public boolean isEnabled() {
        return enabled;
    }

    // ─── tenant-scoped pub/sub ────────────────────────────────────────

    /** Publish a string-encoded message to {@code vance:{tenant}:{channel}}. */
    public void publish(String tenantId, String channel, String message) {
        if (!enabled) return;
        String topic = topicOf(tenantId, channel);
        redis.convertAndSend(topic, message);
    }

    /**
     * Subscribe to {@code vance:{tenant}:{channel}}. Idempotent —
     * subsequent calls with the same {@code (tenantId, channel)} are
     * no-ops (the existing listener remains active).
     *
     * @param handler invoked with {@code (topic, payload)} on every
     *     incoming message.
     */
    public void subscribe(String tenantId, String channel, BiConsumer<String, String> handler) {
        if (!enabled) return;
        String topic = topicOf(tenantId, channel);
        if (listeners.containsKey(topic)) return;
        MessageListener listener = (msg, pattern) -> {
            try {
                handler.accept(topic, new String(msg.getBody()));
            } catch (RuntimeException e) {
                log.warn("redis-msg processing failed on {}: {}", topic, e.toString());
            }
        };
        container.addMessageListener(listener, ChannelTopic.of(topic));
        listeners.put(topic, listener);
    }

    /** Remove the listener registered for {@code vance:{tenant}:{channel}}. */
    public void unsubscribe(String tenantId, String channel) {
        if (!enabled) return;
        String topic = topicOf(tenantId, channel);
        MessageListener listener = listeners.remove(topic);
        if (listener != null) {
            container.removeMessageListener(listener, ChannelTopic.of(topic));
        }
    }

    // ─── cross-tenant pattern pub/sub ─────────────────────────────────

    /**
     * Subscribe to a {@code channel} across all tenants via the
     * {@code vance:*:{channel}} pattern. Handler receives the actual
     * matched topic so the caller can extract the tenant if needed.
     */
    public void subscribeAcrossTenants(String channel, BiConsumer<String, String> handler) {
        if (!enabled) return;
        String pattern = "vance:*:" + channel;
        if (listeners.containsKey(pattern)) return;
        MessageListener listener = (msg, p) -> {
            try {
                String topic = new String(msg.getChannel());
                handler.accept(topic, new String(msg.getBody()));
            } catch (RuntimeException e) {
                log.warn("redis-msg processing failed on pattern {}: {}", pattern, e.toString());
            }
        };
        container.addMessageListener(listener, new PatternTopic(pattern));
        listeners.put(pattern, listener);
    }

    public void unsubscribeAcrossTenants(String channel) {
        if (!enabled) return;
        String pattern = "vance:*:" + channel;
        MessageListener listener = listeners.remove(pattern);
        if (listener != null) {
            container.removeMessageListener(listener, new PatternTopic(pattern));
        }
    }

    // ─── global pub/sub ───────────────────────────────────────────────

    /** Publish to a non-tenant-scoped channel ({@code vance:global:{channel}}). */
    public void publishGlobal(String channel, String message) {
        if (!enabled) return;
        redis.convertAndSend(globalTopicOf(channel), message);
    }

    /** Subscribe to a non-tenant-scoped channel. Idempotent. */
    public void subscribeGlobal(String channel, BiConsumer<String, String> handler) {
        if (!enabled) return;
        String topic = globalTopicOf(channel);
        if (listeners.containsKey(topic)) return;
        MessageListener listener = (msg, pattern) -> {
            try {
                handler.accept(topic, new String(msg.getBody()));
            } catch (RuntimeException e) {
                log.warn("redis-msg processing failed on {}: {}", topic, e.toString());
            }
        };
        container.addMessageListener(listener, ChannelTopic.of(topic));
        listeners.put(topic, listener);
    }

    public void unsubscribeGlobal(String channel) {
        if (!enabled) return;
        String topic = globalTopicOf(channel);
        MessageListener listener = listeners.remove(topic);
        if (listener != null) {
            container.removeMessageListener(listener, ChannelTopic.of(topic));
        }
    }

    // ─── tenant-scoped HASH state with TTL ────────────────────────────
    //
    // Hash keys are tenant-prefixed exactly like pub/sub topics
    // (`vance:{tenant}:{subKey}`) so a tenant-wide Redis FLUSH or scoped
    // ACL works across both surfaces. The TTL is on the *key* — every
    // hashPut refreshes it, so heartbeating any field keeps the whole
    // path's roster alive. When a pod crashes its fields stop being
    // refreshed and the key expires on its own — that's how we replace
    // the old cluster-liveness prune logic.

    /**
     * Set {@code field → value} on the tenant-scoped hash at {@code subKey}
     * and (re)set the key TTL. Idempotent. No-op when Redis is disabled.
     */
    public void hashPut(String tenantId, String subKey, String field, String value, Duration ttl) {
        if (!enabled) return;
        String key = topicOf(tenantId, subKey);
        HashOperations<String, String, String> ops = redis.opsForHash();
        ops.put(key, field, value);
        redis.expire(key, ttl);
    }

    /**
     * Read all fields of the tenant-scoped hash at {@code subKey}.
     * Returns an empty map when Redis is disabled or the key is missing.
     */
    public Map<String, String> hashGetAll(String tenantId, String subKey) {
        if (!enabled) return Collections.emptyMap();
        String key = topicOf(tenantId, subKey);
        return redis.<String, String>opsForHash().entries(key);
    }

    /**
     * Drop one field from the tenant-scoped hash. If that empties the
     * hash, Redis removes the key automatically. No-op when disabled.
     */
    public void hashDelete(String tenantId, String subKey, String field) {
        if (!enabled) return;
        String key = topicOf(tenantId, subKey);
        redis.<String, String>opsForHash().delete(key, field);
    }

    /** Refresh the TTL of an existing hash key. No-op when disabled. */
    public void hashRefreshTtl(String tenantId, String subKey, Duration ttl) {
        if (!enabled) return;
        String key = topicOf(tenantId, subKey);
        redis.expire(key, ttl);
    }

    // ─── topic naming ─────────────────────────────────────────────────

    public static String topicOf(String tenantId, String channel) {
        return "vance:" + tenantId + ":" + channel;
    }

    public static String globalTopicOf(String channel) {
        return "vance:" + GLOBAL + ":" + channel;
    }
}
