package de.mhus.vance.brain.ws.documents;

import de.mhus.vance.api.ws.DocumentPresenceNotification;
import de.mhus.vance.api.ws.DocumentViewer;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code documents}-channel subscriber registry — Redis is the source
 * of truth for the cross-pod viewer roster.
 *
 * <p>Per path, a Redis HASH at {@code vance:{tenant}:documents:viewers:{base64path}}
 * stores one entry per active viewer (field = editorId, value = JSON
 * {@link StoredViewer}). Each {@link #subscribe} writes the field and
 * publishes {@code "podId|path"} on {@code documents.presence}; every
 * pod listening to that pattern re-reads the HASH and pushes a fresh
 * presence frame to its local subscribers.
 *
 * <p>TTL on the key (90s) plus a 30s heartbeat replace the old
 * cluster-liveness prune logic — a crashed pod simply stops refreshing
 * its fields and they expire on their own.
 *
 * <p>When {@code vance.redis.enabled=false}, Redis-backed operations are
 * no-ops: documents-channel still loads, but the presence-roster stays
 * empty (brain works, live features don't).
 */
@Service
@Slf4j
public class DocumentSubscriberRegistry {

    /** Pub/Sub channel used for cross-pod "path changed" signals. */
    static final String CHANNEL = "documents.presence";

    /** Subkey prefix for hash storage; combined with base64(path). */
    static final String HASH_SUBKEY_PREFIX = "documents:viewers:";

    static final Duration VIEWER_KEY_TTL = Duration.ofSeconds(90);
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    /**
     * Local WS-side state — never sent over the wire, used to push
     * presence frames to this pod's own subscribers.
     */
    private record LocalSubscriber(
            WebSocketSession wsSession, ConnectionContext context) {
        DocumentViewer asViewer() {
            return DocumentViewer.builder()
                    .editorId(context.getEditorId())
                    .userId(context.getUserId())
                    .displayName(context.getDisplayName() != null
                            ? context.getDisplayName() : context.getUserId())
                    .build();
        }
    }

    /** Persisted in Redis as JSON. */
    private record StoredViewer(
            String editorId,
            String userId,
            @Nullable String displayName,
            String podId) {
        DocumentViewer toApi() {
            return DocumentViewer.builder()
                    .editorId(editorId)
                    .userId(userId)
                    .displayName(displayName != null ? displayName : userId)
                    .build();
        }
    }

    /** path → ws-session-ids on this pod that subscribe (broadcast targeting). */
    private final Map<String, Set<String>> localSubsByPath = new ConcurrentHashMap<>();

    /** ws-session-id → set of paths (cleanup + heartbeat index). */
    private final Map<String, Set<String>> bySession = new ConcurrentHashMap<>();

    /** ws-session-id → LocalSubscriber snapshot (push target). */
    private final Map<String, LocalSubscriber> bySessionInfo = new ConcurrentHashMap<>();

    private final WebSocketSender sender;
    private final VanceRedisMessagingService redis;
    private final ObjectMapper objectMapper;

    /**
     * Random per-process identity. Used to ignore our own pub/sub echoes
     * — the publish-side adds it so listeners can skip self-loopback.
     */
    private final String podId = UUID.randomUUID().toString();

    public DocumentSubscriberRegistry(
            WebSocketSender sender,
            VanceRedisMessagingService redis,
            ObjectMapper objectMapper) {
        this.sender = sender;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        redis.subscribeAcrossTenants(CHANNEL, this::onRemoteChanged);
        log.debug("DocumentSubscriberRegistry: podId={} redis.enabled={}",
                podId, redis.isEnabled());
    }

    @PreDestroy
    public void stop() {
        // Best-effort: clear our entries from every active path so peers
        // don't have to wait for TTL to drop us out of the roster.
        for (Map.Entry<String, Set<String>> e : new HashSet<>(localSubsByPath.entrySet())) {
            String path = e.getKey();
            for (String wsId : new HashSet<>(e.getValue())) {
                LocalSubscriber sub = bySessionInfo.get(wsId);
                if (sub == null) continue;
                try {
                    String tenantId = sub.context().getTenantId();
                    redis.hashDelete(tenantId, hashSubKey(path), sub.context().getEditorId());
                    redis.publish(tenantId, CHANNEL, encodeChangedPayload(path));
                } catch (RuntimeException ex) {
                    log.debug("documents shutdown cleanup failed for ws={} path={}: {}",
                            wsId, path, ex.toString());
                }
            }
        }
        redis.unsubscribeAcrossTenants(CHANNEL);
    }

    // ─── public API ─────────────────────────────────────────────────────

    public void subscribe(WebSocketSession wsSession, ConnectionContext ctx, String path) {
        String wsId = wsSession.getId();
        bySession.computeIfAbsent(wsId, k -> ConcurrentHashMap.newKeySet()).add(path);
        bySessionInfo.putIfAbsent(wsId, new LocalSubscriber(wsSession, ctx));
        localSubsByPath.computeIfAbsent(path, k -> ConcurrentHashMap.newKeySet()).add(wsId);

        writeToRedis(ctx.getTenantId(), path, ctx);
        broadcastPresence(path, ctx.getTenantId());
        log.debug("documents.subscribe: ws='{}' user='{}' path='{}'",
                wsId, ctx.getUserId(), path);
    }

    public void unsubscribe(WebSocketSession wsSession, String path) {
        String wsId = wsSession.getId();
        if (!removeLocal(wsId, path)) return;
        LocalSubscriber sub = bySessionInfo.get(wsId);
        String tenantId = sub != null ? sub.context().getTenantId() : null;
        if (tenantId != null) {
            redis.hashDelete(tenantId, hashSubKey(path), sub.context().getEditorId());
            redis.publish(tenantId, CHANNEL, encodeChangedPayload(path));
            broadcastPresence(path, tenantId);
        }
        // If this was the session's last path, drop the info entry too.
        Set<String> remaining = bySession.get(wsId);
        if (remaining == null || remaining.isEmpty()) {
            bySessionInfo.remove(wsId);
        }
        log.debug("documents.unsubscribe: ws='{}' path='{}'", wsId, path);
    }

    public void unsubscribeAll(WebSocketSession wsSession) {
        String wsId = wsSession.getId();
        Set<String> paths = bySession.remove(wsId);
        if (paths == null || paths.isEmpty()) {
            bySessionInfo.remove(wsId);
            return;
        }
        LocalSubscriber sub = bySessionInfo.remove(wsId);
        String tenantId = sub != null ? sub.context().getTenantId() : null;
        for (String path : paths) {
            Set<String> ids = localSubsByPath.get(path);
            if (ids != null) {
                ids.remove(wsId);
                if (ids.isEmpty()) localSubsByPath.remove(path);
            }
            if (tenantId != null && sub != null) {
                redis.hashDelete(tenantId, hashSubKey(path), sub.context().getEditorId());
                redis.publish(tenantId, CHANNEL, encodeChangedPayload(path));
                broadcastPresence(path, tenantId);
            }
        }
        log.debug("documents.unsubscribeAll: ws='{}' paths={}", wsId, paths.size());
    }

    public Set<String> pathsOf(WebSocketSession wsSession) {
        Set<String> paths = bySession.get(wsSession.getId());
        return paths == null ? Collections.emptySet() : Set.copyOf(paths);
    }

    /**
     * Invoke {@code action} for every WS session on this pod that is
     * subscribed to {@code path}. Used by sibling services (e.g.
     * {@code DocumentChangedBroadcaster}) that need to push a frame to
     * the same audience as the presence broadcasts. Returns immediately
     * when nobody on this pod listens to the path.
     */
    public void forEachLocalSubscriber(String path,
            java.util.function.BiConsumer<WebSocketSession, ConnectionContext> action) {
        Set<String> wsIds = localSubsByPath.get(path);
        if (wsIds == null || wsIds.isEmpty()) return;
        for (String wsId : wsIds) {
            LocalSubscriber sub = bySessionInfo.get(wsId);
            if (sub == null) continue;
            action.accept(sub.wsSession(), sub.context());
        }
    }

    /** {@code true} when at least one WS session on this pod subscribes to {@code path}. */
    public boolean hasLocalSubscribers(String path) {
        Set<String> wsIds = localSubsByPath.get(path);
        return wsIds != null && !wsIds.isEmpty();
    }

    /**
     * Full viewer roster for {@code path} as Redis sees it — local +
     * cross-pod, no self-filter applied. Used by tests / admin probes.
     * Returns empty when Redis is disabled.
     */
    public List<DocumentViewer> viewersOf(String path) {
        // We need a tenant to read; pick one from any local subscriber
        // on this path. Tests that call viewersOf usually have one set up.
        Set<String> localIds = localSubsByPath.get(path);
        if (localIds == null || localIds.isEmpty()) return Collections.emptyList();
        String wsId = localIds.iterator().next();
        LocalSubscriber any = bySessionInfo.get(wsId);
        if (any == null) return Collections.emptyList();
        return viewersOf(any.context().getTenantId(), path);
    }

    /** Same as {@link #viewersOf(String)} but tenant is given explicitly. */
    public List<DocumentViewer> viewersOf(String tenantId, String path) {
        Map<String, String> hash = redis.hashGetAll(tenantId, hashSubKey(path));
        if (hash.isEmpty()) return Collections.emptyList();
        List<DocumentViewer> out = new ArrayList<>(hash.size());
        for (String json : hash.values()) {
            try {
                StoredViewer sv = objectMapper.readValue(json, StoredViewer.class);
                out.add(sv.toApi());
            } catch (JacksonException e) {
                log.debug("documents: skipping malformed viewer entry: {}", e.toString());
            }
        }
        return out;
    }

    // ─── heartbeat ──────────────────────────────────────────────────────

    /**
     * Refresh every local subscriber's Redis-hash field so the key-TTL
     * stays alive. Skips work entirely when Redis is disabled.
     */
    @Scheduled(
            fixedDelayString = "${vance.documents.presence.heartbeat-ms:30000}",
            initialDelayString = "${vance.documents.presence.heartbeat-ms:30000}")
    public void heartbeat() {
        if (!redis.isEnabled()) return;
        for (Map.Entry<String, LocalSubscriber> e : bySessionInfo.entrySet()) {
            LocalSubscriber sub = e.getValue();
            Set<String> paths = bySession.get(e.getKey());
            if (paths == null) continue;
            for (String path : paths) {
                writeToRedis(sub.context().getTenantId(), path, sub.context());
            }
        }
    }

    // ─── cross-pod sync ────────────────────────────────────────────────

    private void onRemoteChanged(String topic, String body) {
        // topic format: vance:{tenantId}:documents.presence
        String tenantId = parseTenantFromTopic(topic);
        if (tenantId == null) return;
        String[] parts = body.split("\\|", 2);
        if (parts.length != 2) return;
        String senderPodId = parts[0];
        if (Objects.equals(senderPodId, podId)) return;  // own echo
        String path = decodeChangedPath(parts[1]);
        if (path == null) return;
        broadcastPresence(path, tenantId);
    }

    // ─── broadcast ─────────────────────────────────────────────────────

    private void broadcastPresence(String path, String tenantId) {
        Set<String> localIds = localSubsByPath.get(path);
        if (localIds == null || localIds.isEmpty()) return;

        List<DocumentViewer> allViewers = viewersOf(tenantId, path);

        for (String wsId : localIds) {
            LocalSubscriber recipient = bySessionInfo.get(wsId);
            if (recipient == null) continue;
            String selfEditorId = recipient.context().getEditorId();
            List<DocumentViewer> filtered = new ArrayList<>(allViewers.size());
            for (DocumentViewer v : allViewers) {
                if (!Objects.equals(v.getEditorId(), selfEditorId)) {
                    filtered.add(v);
                }
            }
            DocumentPresenceNotification payload = DocumentPresenceNotification.builder()
                    .path(path)
                    .viewers(filtered)
                    .build();
            WebSocketEnvelope envelope = WebSocketEnvelope.notification(
                    MessageType.DOCUMENT_PRESENCE, payload);
            try {
                sender.sendOnChannel(recipient.wsSession(), "documents", envelope);
            } catch (IOException e) {
                log.debug("documents.presence push failed ws='{}' path='{}': {}",
                        recipient.wsSession().getId(), path, e.toString());
            }
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private void writeToRedis(String tenantId, String path, ConnectionContext ctx) {
        StoredViewer sv = new StoredViewer(
                ctx.getEditorId(),
                ctx.getUserId(),
                ctx.getDisplayName(),
                podId);
        try {
            String json = objectMapper.writeValueAsString(sv);
            redis.hashPut(tenantId, hashSubKey(path), ctx.getEditorId(), json, VIEWER_KEY_TTL);
            redis.publish(tenantId, CHANNEL, encodeChangedPayload(path));
        } catch (JacksonException e) {
            log.warn("documents: failed to serialise viewer for path={}: {}", path, e.toString());
        }
    }

    /** Remove a (wsId, path) local mapping. Returns {@code true} if state changed. */
    private boolean removeLocal(String wsId, String path) {
        Set<String> paths = bySession.get(wsId);
        if (paths == null || !paths.remove(path)) return false;
        if (paths.isEmpty()) bySession.remove(wsId);
        Set<String> ids = localSubsByPath.get(path);
        if (ids != null) {
            ids.remove(wsId);
            if (ids.isEmpty()) localSubsByPath.remove(path);
        }
        return true;
    }

    static String hashSubKey(String path) {
        return HASH_SUBKEY_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }

    private String encodeChangedPayload(String path) {
        return podId + "|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }

    private static @Nullable String decodeChangedPath(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable String parseTenantFromTopic(String topic) {
        // vance:{tenantId}:documents.presence — tenantId is the middle segment.
        if (!topic.startsWith("vance:")) return null;
        int firstColon = "vance:".length();
        int secondColon = topic.indexOf(':', firstColon);
        if (secondColon < 0) return null;
        return topic.substring(firstColon, secondColon);
    }

    /** Test hook: stable pod identity for assertions. */
    String podIdForTests() {
        return podId;
    }
}
