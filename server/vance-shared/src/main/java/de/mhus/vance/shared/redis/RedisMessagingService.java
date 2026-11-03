package de.mhus.vance.shared.redis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

/**
 * Redis-backed {@link VanceRedisMessagingService}: the only place that talks to Redis directly.
 * Active when {@code vance.redis.enabled=true}; otherwise {@link LocalMessagingService} serves the
 * same contract inside the process.
 *
 * <p>Topic and hash-key naming, subscribe idempotence and pattern semantics are documented on the
 * interface.
 */
@Service
@ConditionalOnProperty(prefix = "vance.redis", name = "enabled", havingValue = "true")
@Slf4j
public class RedisMessagingService implements VanceRedisMessagingService {

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

    public RedisMessagingService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<RedisMessageListenerContainer> containerProvider) {
        this.redis = redisProvider.getIfAvailable();
        this.container = containerProvider.getIfAvailable();
        this.enabled = redis != null && container != null;
        if (!enabled) {
            log.info("RedisMessagingService: disabled "
                    + "(vance.redis.enabled=false) — cross-pod pub/sub is a no-op. "
                    + "Live features work pod-local; multi-pod fan-out is not available.");
        }
    }

    /** {@code true} when Redis is configured and the Beans are wired. */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // ─── tenant-scoped pub/sub ────────────────────────────────────────

    /** Publish a string-encoded message to {@code vance:{tenant}:{channel}}. */
    @Override
    public void publish(String tenantId, String channel, String message) {
        if (!enabled) return;
        String topic = VanceRedisMessagingService.topicOf(tenantId, channel);
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
    @Override
    public void subscribe(String tenantId, String channel, BiConsumer<String, String> handler) {
        if (!enabled) return;
        String topic = VanceRedisMessagingService.topicOf(tenantId, channel);
        if (listeners.containsKey(topic)) return;
        MessageListener listener = (msg, pattern) -> {
            try {
                handler.accept(topic, new String(msg.getBody(), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                log.warn("redis-msg processing failed on {}: {}", topic, e.toString());
            }
        };
        container.addMessageListener(listener, ChannelTopic.of(topic));
        listeners.put(topic, listener);
    }

    /** Remove the listener registered for {@code vance:{tenant}:{channel}}. */
    @Override
    public void unsubscribe(String tenantId, String channel) {
        if (!enabled) return;
        String topic = VanceRedisMessagingService.topicOf(tenantId, channel);
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
    @Override
    public void subscribeAcrossTenants(String channel, BiConsumer<String, String> handler) {
        if (!enabled) return;
        String pattern = "vance:*:" + channel;
        if (listeners.containsKey(pattern)) return;
        MessageListener listener = (msg, p) -> {
            try {
                String topic = new String(msg.getChannel(), StandardCharsets.UTF_8);
                handler.accept(topic, new String(msg.getBody(), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                log.warn("redis-msg processing failed on pattern {}: {}", pattern, e.toString());
            }
        };
        container.addMessageListener(listener, new PatternTopic(pattern));
        listeners.put(pattern, listener);
    }

    @Override
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
    @Override
    public void publishGlobal(String channel, String message) {
        if (!enabled) return;
        redis.convertAndSend(VanceRedisMessagingService.globalTopicOf(channel), message);
    }

    /** Subscribe to a non-tenant-scoped channel. Idempotent. */
    @Override
    public void subscribeGlobal(String channel, BiConsumer<String, String> handler) {
        if (!enabled) return;
        String topic = VanceRedisMessagingService.globalTopicOf(channel);
        if (listeners.containsKey(topic)) return;
        MessageListener listener = (msg, pattern) -> {
            try {
                handler.accept(topic, new String(msg.getBody(), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                log.warn("redis-msg processing failed on {}: {}", topic, e.toString());
            }
        };
        container.addMessageListener(listener, ChannelTopic.of(topic));
        listeners.put(topic, listener);
    }

    @Override
    public void unsubscribeGlobal(String channel) {
        if (!enabled) return;
        String topic = VanceRedisMessagingService.globalTopicOf(channel);
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
    @Override
    public void hashPut(String tenantId, String subKey, String field, String value, Duration ttl) {
        if (!enabled) return;
        String key = VanceRedisMessagingService.topicOf(tenantId, subKey);
        HashOperations<String, String, String> ops = redis.opsForHash();
        ops.put(key, field, value);
        redis.expire(key, ttl);
    }

    /**
     * Read all fields of the tenant-scoped hash at {@code subKey}.
     * Returns an empty map when Redis is disabled or the key is missing.
     */
    @Override
    public Map<String, String> hashGetAll(String tenantId, String subKey) {
        if (!enabled) return Collections.emptyMap();
        String key = VanceRedisMessagingService.topicOf(tenantId, subKey);
        return redis.<String, String>opsForHash().entries(key);
    }

    /**
     * Drop one field from the tenant-scoped hash. If that empties the
     * hash, Redis removes the key automatically. No-op when disabled.
     */
    @Override
    public void hashDelete(String tenantId, String subKey, String field) {
        if (!enabled) return;
        String key = VanceRedisMessagingService.topicOf(tenantId, subKey);
        redis.<String, String>opsForHash().delete(key, field);
    }

    /** Refresh the TTL of an existing hash key. No-op when disabled. */
    @Override
    public void hashRefreshTtl(String tenantId, String subKey, Duration ttl) {
        if (!enabled) return;
        String key = VanceRedisMessagingService.topicOf(tenantId, subKey);
        redis.expire(key, ttl);
    }
}
