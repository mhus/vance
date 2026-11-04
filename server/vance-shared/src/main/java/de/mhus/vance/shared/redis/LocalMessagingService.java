package de.mhus.vance.shared.redis;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * In-process {@link VanceRedisMessagingService} for a single node: the live features — document
 * changes, presence, project and runner status — work without a Redis. Registered by
 * {@link LocalMessagingAutoConfiguration} whenever no other implementation (the Redis one of the
 * cloud edition) is on the classpath and enabled.
 *
 * <p><b>Delivery is decoupled from the publisher.</b> {@link #publish} only appends to a queue and
 * returns; one dispatcher thread takes messages off it and calls the handlers. That keeps a
 * request thread (a document write) from running subscriber code, keeps a slow or failing handler
 * from reaching the publisher, and makes a handler that publishes again a plain enqueue instead of
 * a recursion. One thread means messages arrive in the order they were published, like on a Redis
 * connection.
 *
 * <p>The queue is not bounded — dropping a document change would be worse than waiting — but a
 * handler that blocks stalls every live feature behind it, so a backlog is reported once it passes
 * {@value #BACKLOG_WARN} messages. Handlers must hand work off, not wait on a client.
 *
 * <p>Hash and value state live in memory with a per-key expiry that is checked on read, so an
 * expired entry is never served. Entries nobody reads again are dropped by a sweep that runs with
 * the writes, at most once a {@value #SWEEP_INTERVAL_MS} ms. {@link #isEnabled()} is {@code false}: there is no second pod to reach, so callers skip
 * their cross-pod extras.
 */
@Slf4j
public final class LocalMessagingService implements VanceRedisMessagingService {

    /** Marks the end of the queue; taken by the dispatcher on shutdown. */
    private static final Message STOP = new Message("", "");

    /** Queue depth at which a backlog is reported. */
    static final int BACKLOG_WARN = 10_000;

    /** Shortest time between two sweeps of expired state. */
    static final long SWEEP_INTERVAL_MS = 60_000L;

    private record Message(String topic, String payload) {}

    private record Subscription(String key, @Nullable String pattern, BiConsumer<String, String> handler) {
        boolean matches(String topic) {
            if (pattern == null) return key.equals(topic);
            // Redis glob "vance:*:<channel>": "*" spans any text, colons included.
            String suffix = pattern.substring(pattern.indexOf('*') + 1);
            return topic.startsWith("vance:")
                    && topic.length() >= "vance:".length() + suffix.length()
                    && topic.endsWith(suffix);
        }
    }

    private static final class HashEntry {
        final Map<String, String> fields = new HashMap<>();
        long expiresAtMs;
    }

    private record ValueEntry(String value, long expiresAtMs) {}

    private final Clock clock;
    private final BlockingQueue<Message> queue = new LinkedBlockingQueue<>();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, HashEntry> hashes = new HashMap<>();
    private final Map<String, ValueEntry> values = new HashMap<>();
    private final Thread dispatcher;
    private volatile boolean backlogReported;
    private final Object sweepLock = new Object();
    private long lastSweepMs;

    public LocalMessagingService() {
        this(Clock.systemUTC());
    }

    LocalMessagingService(Clock clock) {
        this.clock = clock;
        this.dispatcher =
                Thread.ofPlatform().name("vance-local-messaging").daemon().start(this::dispatchLoop);
        log.info("LocalMessagingService: no Redis module — live features run "
                + "inside this process; there is no fan-out to other pods.");
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @PreDestroy
    void stop() {
        queue.add(STOP);
    }

    // ─── dispatching ──────────────────────────────────────────────────

    private void dispatchLoop() {
        while (true) {
            Message message;
            try {
                message = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (message == STOP) return;
            for (Subscription subscription : subscriptions.values()) {
                if (!subscription.matches(message.topic())) continue;
                try {
                    subscription.handler().accept(message.topic(), message.payload());
                } catch (RuntimeException e) {
                    log.warn("local-msg processing failed on {}: {}", message.topic(), e.toString());
                }
            }
        }
    }

    private void enqueue(String topic, String message) {
        queue.add(new Message(topic, message));
        int depth = queue.size();
        if (depth >= BACKLOG_WARN) {
            if (!backlogReported) {
                backlogReported = true;
                log.warn("local-msg backlog of {} messages — a subscriber handler is blocking the dispatcher", depth);
            }
        } else if (depth < BACKLOG_WARN / 2) {
            backlogReported = false;
        }
    }

    private void register(String key, @Nullable String pattern, BiConsumer<String, String> handler) {
        subscriptions.putIfAbsent(key, new Subscription(key, pattern, handler));
    }

    // ─── pub/sub ──────────────────────────────────────────────────────

    @Override
    public void publish(String tenantId, String channel, String message) {
        enqueue(VanceRedisMessagingService.topicOf(tenantId, channel), message);
    }

    @Override
    public void subscribe(String tenantId, String channel, BiConsumer<String, String> handler) {
        String topic = VanceRedisMessagingService.topicOf(tenantId, channel);
        register(topic, null, handler);
    }

    @Override
    public void unsubscribe(String tenantId, String channel) {
        subscriptions.remove(VanceRedisMessagingService.topicOf(tenantId, channel));
    }

    @Override
    public void subscribeAcrossTenants(String channel, BiConsumer<String, String> handler) {
        String pattern = "vance:*:" + channel;
        register(pattern, pattern, handler);
    }

    @Override
    public void unsubscribeAcrossTenants(String channel) {
        subscriptions.remove("vance:*:" + channel);
    }

    @Override
    public void publishGlobal(String channel, String message) {
        enqueue(VanceRedisMessagingService.globalTopicOf(channel), message);
    }

    @Override
    public void subscribeGlobal(String channel, BiConsumer<String, String> handler) {
        register(VanceRedisMessagingService.globalTopicOf(channel), null, handler);
    }

    @Override
    public void unsubscribeGlobal(String channel) {
        subscriptions.remove(VanceRedisMessagingService.globalTopicOf(channel));
    }

    // ─── hash state with TTL ──────────────────────────────────────────

    @Override
    public void hashPut(String tenantId, String subKey, String field, String value, Duration ttl) {
        String key = VanceRedisMessagingService.topicOf(tenantId, subKey);
        sweepIfDue();
        synchronized (hashes) {
            HashEntry entry = liveEntry(key);
            if (entry == null) {
                entry = new HashEntry();
                hashes.put(key, entry);
            }
            entry.fields.put(field, value);
            entry.expiresAtMs = clock.millis() + ttl.toMillis();
        }
    }

    @Override
    public Map<String, String> hashGetAll(String tenantId, String subKey) {
        synchronized (hashes) {
            HashEntry entry = liveEntry(VanceRedisMessagingService.topicOf(tenantId, subKey));
            return entry == null ? Collections.emptyMap() : new HashMap<>(entry.fields);
        }
    }

    @Override
    public void hashDelete(String tenantId, String subKey, String field) {
        String key = VanceRedisMessagingService.topicOf(tenantId, subKey);
        synchronized (hashes) {
            HashEntry entry = liveEntry(key);
            if (entry == null) return;
            entry.fields.remove(field);
            if (entry.fields.isEmpty()) hashes.remove(key);
        }
    }

    @Override
    public void hashRefreshTtl(String tenantId, String subKey, Duration ttl) {
        synchronized (hashes) {
            HashEntry entry = liveEntry(VanceRedisMessagingService.topicOf(tenantId, subKey));
            if (entry != null) entry.expiresAtMs = clock.millis() + ttl.toMillis();
        }
    }

    // ─── single values with TTL ───────────────────────────────────────

    @Override
    public void valuePut(String tenantId, String subKey, String value, Duration ttl) {
        String key = VanceRedisMessagingService.topicOf(tenantId, subKey);
        sweepIfDue();
        synchronized (values) {
            values.put(key, new ValueEntry(value, clock.millis() + ttl.toMillis()));
        }
    }

    @Override
    public boolean valuePutIfAbsent(String tenantId, String subKey, String value, Duration ttl) {
        String key = VanceRedisMessagingService.topicOf(tenantId, subKey);
        synchronized (values) {
            if (liveValue(key) != null) return false;
            values.put(key, new ValueEntry(value, clock.millis() + ttl.toMillis()));
            return true;
        }
    }

    @Override
    public Optional<String> valueGet(String tenantId, String subKey) {
        synchronized (values) {
            ValueEntry entry = liveValue(VanceRedisMessagingService.topicOf(tenantId, subKey));
            return entry == null ? Optional.empty() : Optional.of(entry.value());
        }
    }

    @Override
    public boolean valueExists(String tenantId, String subKey) {
        synchronized (values) {
            return liveValue(VanceRedisMessagingService.topicOf(tenantId, subKey)) != null;
        }
    }

    @Override
    public void valueDelete(String tenantId, String subKey) {
        synchronized (values) {
            values.remove(VanceRedisMessagingService.topicOf(tenantId, subKey));
        }
    }

    /** Drops every expired entry, at most once per {@link #SWEEP_INTERVAL_MS}. */
    private void sweepIfDue() {
        long now = clock.millis();
        synchronized (sweepLock) {
            if (now - lastSweepMs < SWEEP_INTERVAL_MS) return;
            lastSweepMs = now;
        }
        synchronized (hashes) {
            hashes.values().removeIf(entry -> entry.expiresAtMs <= now);
        }
        synchronized (values) {
            values.values().removeIf(entry -> entry.expiresAtMs() <= now);
        }
    }

    /** Number of hash and value keys held, expired ones included until swept. */
    int storedKeys() {
        int count;
        synchronized (hashes) {
            count = hashes.size();
        }
        synchronized (values) {
            return count + values.size();
        }
    }

    /** The value for {@code key} if it has not expired; an expired one is dropped. Hold the lock. */
    private @Nullable ValueEntry liveValue(String key) {
        ValueEntry entry = values.get(key);
        if (entry == null) return null;
        if (entry.expiresAtMs() <= clock.millis()) {
            values.remove(key);
            return null;
        }
        return entry;
    }

    /** The entry for {@code key} if it has not expired; an expired one is dropped. Hold the lock. */
    private @Nullable HashEntry liveEntry(String key) {
        HashEntry entry = hashes.get(key);
        if (entry == null) return null;
        if (entry.expiresAtMs <= clock.millis()) {
            hashes.remove(key);
            return null;
        }
        return entry;
    }
}
