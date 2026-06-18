package de.mhus.vance.brain.ws.documents;

import de.mhus.vance.api.ws.DocumentPresenceNotification;
import de.mhus.vance.api.ws.DocumentViewer;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-pod registry of {@code documents}-channel subscribers, with
 * cross-pod presence aggregation via Redis pub/sub.
 *
 * <p>Local state ({@code byPath}, {@code bySession}) tracks subscribers
 * that opened their WebSocket on **this** pod. A separate shadow map
 * ({@code remoteByPath}) tracks viewers reported by **other** pods via
 * the {@code vance:{tenant}:documents.presence} Redis topic. Presence
 * broadcasts merge both views.
 *
 * <p>The shared {@link VanceRedisMessagingService} is the only
 * cross-pod transport — same bus we'll use for {@code documents.changed},
 * cross-session NOTIFY, inbox-push and friends. See
 * {@code planning/document-presence.md} §5.5.
 */
@Service
@Slf4j
public class DocumentSubscriberRegistry {

    private static final String CHANNEL = "documents.presence";
    private static final String CHANNEL_TENANT_KEY = "documents.presence";

    /** Single-document subscriber entry: who is subscribed and how to reach them. */
    private record Subscriber(
            WebSocketSession wsSession,
            String editorId,
            String userId,
            @Nullable String displayName,
            String tenantId) {

        DocumentViewer asViewer() {
            return DocumentViewer.builder()
                    .editorId(editorId)
                    .userId(userId)
                    .displayName(displayName != null ? displayName : userId)
                    .build();
        }
    }

    /** path → subscribers on this pod. */
    private final Map<String, Set<Subscriber>> byPath = new ConcurrentHashMap<>();

    /** ws-session-id → set of paths this session is subscribed to (cleanup index). */
    private final Map<String, Set<String>> bySession = new ConcurrentHashMap<>();

    /**
     * Remote presence shadow: path → (sourcePodId → set of viewers reported
     * by that pod). Updated on incoming Redis {@link PresenceDelta} messages,
     * purged when a pod drops out of the cluster (see {@link #pruneDeadPods}).
     */
    private final Map<String, Map<String, Set<DocumentViewer>>> remoteByPath = new ConcurrentHashMap<>();

    private final WebSocketSender sender;
    private final VanceRedisMessagingService messaging;
    private final ClusterService clusterService;
    private final ObjectMapper objectMapper;

    public DocumentSubscriberRegistry(
            WebSocketSender sender,
            VanceRedisMessagingService messaging,
            ClusterService clusterService,
            ObjectMapper objectMapper) {
        this.sender = sender;
        this.messaging = messaging;
        this.clusterService = clusterService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        // Single pattern subscription covers every tenant — incoming
        // payload carries the tenantId so we don't need to subscribe per
        // tenant. Saves listeners.
        messaging.subscribeAcrossTenants(CHANNEL_TENANT_KEY, this::onRemoteDelta);
        log.debug("DocumentSubscriberRegistry: subscribed to Redis topic vance:*:{}", CHANNEL);
    }

    @PreDestroy
    public void stop() {
        try {
            // Best-effort: tell peer pods to drop our presence so users
            // see roster updates instantly on graceful shutdown instead
            // of waiting for the heartbeat-driven pruning.
            for (Subscriber sub : allLocalSubscribers()) {
                publishDelta(sub.tenantId, PresenceDelta.Action.CLEAR_POD, null, null);
                break; // one CLEAR_POD per tenant is enough; emit once per known tenantId
            }
            Set<String> tenants = new HashSet<>();
            for (Subscriber sub : allLocalSubscribers()) tenants.add(sub.tenantId);
            for (String t : tenants) {
                publishDelta(t, PresenceDelta.Action.CLEAR_POD, null, null);
            }
        } catch (RuntimeException e) {
            log.debug("DocumentSubscriberRegistry: graceful CLEAR_POD broadcast failed: {}", e.toString());
        }
        messaging.unsubscribeAcrossTenants(CHANNEL_TENANT_KEY);
    }

    // ─── public API ─────────────────────────────────────────────────────

    public void subscribe(WebSocketSession wsSession, ConnectionContext ctx, String path) {
        Subscriber subscriber = new Subscriber(
                wsSession, ctx.getConnectionId(), ctx.getUserId(),
                ctx.getDisplayName(), ctx.getTenantId());
        boolean added = byPath
                .computeIfAbsent(path, p -> ConcurrentHashMap.newKeySet())
                .add(subscriber);
        if (added) {
            bySession
                    .computeIfAbsent(wsSession.getId(), s -> ConcurrentHashMap.newKeySet())
                    .add(path);
            publishDelta(ctx.getTenantId(), PresenceDelta.Action.ADD, path, subscriber.asViewer());
            log.debug("documents.subscribe: ws='{}' user='{}' path='{}'",
                    wsSession.getId(), ctx.getUserId(), path);
        }
        broadcastPresence(path);
    }

    public void unsubscribe(WebSocketSession wsSession, String path) {
        Subscriber removed = removeSubscriberFromPath(wsSession, path);
        if (removed != null) {
            publishDelta(removed.tenantId, PresenceDelta.Action.REMOVE, path, removed.asViewer());
            log.debug("documents.unsubscribe: ws='{}' path='{}'", wsSession.getId(), path);
            broadcastPresence(path);
        }
    }

    public void unsubscribeAll(WebSocketSession wsSession) {
        Set<String> paths = bySession.remove(wsSession.getId());
        if (paths == null || paths.isEmpty()) return;
        List<Subscriber> removedSubs = new ArrayList<>(paths.size());
        for (String path : paths) {
            Subscriber removed = removeSubscriberFromPath(wsSession, path);
            if (removed != null) removedSubs.add(removed);
        }
        for (Subscriber removed : removedSubs) {
            publishDelta(removed.tenantId(), PresenceDelta.Action.REMOVE,
                    pathForViewerOnSession(removed, paths), removed.asViewer());
        }
        for (String path : paths) {
            broadcastPresence(path);
        }
        log.debug("documents.unsubscribeAll: ws='{}' paths={}", wsSession.getId(), paths.size());
    }

    public Set<String> pathsOf(WebSocketSession wsSession) {
        Set<String> paths = bySession.get(wsSession.getId());
        return paths == null ? Collections.emptySet() : Set.copyOf(paths);
    }

    /**
     * Aggregated viewer view for tests / admin: local + remote, no
     * self-filter applied (caller does the per-recipient projection).
     */
    public List<DocumentViewer> viewersOf(String path) {
        List<DocumentViewer> viewers = new ArrayList<>();
        Set<Subscriber> local = byPath.get(path);
        if (local != null) {
            for (Subscriber s : local) viewers.add(s.asViewer());
        }
        Map<String, Set<DocumentViewer>> remote = remoteByPath.get(path);
        if (remote != null) {
            for (Set<DocumentViewer> perPod : remote.values()) {
                viewers.addAll(perPod);
            }
        }
        return viewers;
    }

    /**
     * Periodic cleanup: drop {@link #remoteByPath} entries for pods that
     * are no longer in {@link ClusterService#liveClusterNodeNames}.
     * Re-broadcasts presence on every affected path.
     */
    @Scheduled(fixedDelayString = "${vance.documents.presence.prune-interval-ms:30000}",
            initialDelayString = "${vance.documents.presence.prune-interval-ms:30000}")
    public void pruneDeadPods() {
        Set<String> live;
        try {
            live = clusterService.liveClusterNodeNames();
        } catch (RuntimeException e) {
            log.debug("Cluster liveness lookup failed during presence prune: {}", e.toString());
            return;
        }
        Set<String> affectedPaths = new HashSet<>();
        for (Map.Entry<String, Map<String, Set<DocumentViewer>>> e : remoteByPath.entrySet()) {
            Map<String, Set<DocumentViewer>> perPod = e.getValue();
            boolean changed = perPod.keySet().retainAll(live);
            if (changed) affectedPaths.add(e.getKey());
            if (perPod.isEmpty()) {
                remoteByPath.compute(e.getKey(),
                        (k, v) -> (v == null || v.isEmpty()) ? null : v);
            }
        }
        for (String path : affectedPaths) {
            broadcastPresence(path);
        }
    }

    // ─── internals ──────────────────────────────────────────────────────

    private @Nullable Subscriber removeSubscriberFromPath(WebSocketSession wsSession, String path) {
        Set<Subscriber> subs = byPath.get(path);
        if (subs == null) return null;
        Subscriber removed = null;
        for (Subscriber s : subs) {
            if (Objects.equals(s.wsSession.getId(), wsSession.getId())) {
                removed = s;
                break;
            }
        }
        if (removed != null) subs.remove(removed);
        if (subs.isEmpty()) {
            byPath.compute(path, (p, current) -> (current == null || current.isEmpty()) ? null : current);
        }
        Set<String> paths = bySession.get(wsSession.getId());
        if (paths != null) {
            paths.remove(path);
            if (paths.isEmpty()) {
                bySession.compute(wsSession.getId(),
                        (k, v) -> (v == null || v.isEmpty()) ? null : v);
            }
        }
        return removed;
    }

    private void broadcastPresence(String path) {
        Set<Subscriber> localSubs = byPath.get(path);
        Map<String, Set<DocumentViewer>> remoteSubs = remoteByPath.get(path);
        if ((localSubs == null || localSubs.isEmpty())
                && (remoteSubs == null || remoteSubs.isEmpty())) {
            // Both sides empty — nothing to push. Local subscribers got
            // their last presence push before they left; nobody's listening.
            return;
        }

        // Snapshot all viewers (local + remote) once.
        List<DocumentViewer> allViewers = new ArrayList<>();
        Set<Subscriber> localSnapshot = localSubs == null ? Set.of() : new HashSet<>(localSubs);
        for (Subscriber s : localSnapshot) allViewers.add(s.asViewer());
        if (remoteSubs != null) {
            for (Set<DocumentViewer> perPod : remoteSubs.values()) {
                allViewers.addAll(perPod);
            }
        }

        // Only local subscribers receive the push; remote pods do their
        // own presence-push to their local subscribers via the Redis
        // delta we publish from subscribe/unsubscribe.
        for (Subscriber recipient : localSnapshot) {
            List<DocumentViewer> filtered = new ArrayList<>(allViewers.size());
            for (DocumentViewer v : allViewers) {
                if (!Objects.equals(v.getEditorId(), recipient.editorId)) {
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
                sender.sendOnChannel(recipient.wsSession, "documents", envelope);
            } catch (IOException e) {
                log.debug("documents.presence push failed for ws='{}' path='{}': {}",
                        recipient.wsSession.getId(), path, e.toString());
            }
        }
    }

    // ─── Redis cross-pod sync ──────────────────────────────────────────

    private void publishDelta(String tenantId, PresenceDelta.Action action,
            @Nullable String path, @Nullable DocumentViewer viewer) {
        PresenceDelta delta = PresenceDelta.builder()
                .podId(clusterService.selfNodeName())
                .tenantId(tenantId)
                .action(action)
                .path(path)
                .viewer(viewer)
                .build();
        try {
            String json = objectMapper.writeValueAsString(delta);
            messaging.publish(tenantId, CHANNEL_TENANT_KEY, json);
        } catch (JacksonException e) {
            log.warn("DocumentSubscriberRegistry: serialise delta failed: {}", e.toString());
        } catch (RuntimeException e) {
            log.warn("DocumentSubscriberRegistry: redis publish failed: {}", e.toString());
        }
    }

    private void onRemoteDelta(String topic, String json) {
        PresenceDelta delta;
        try {
            delta = objectMapper.readValue(json, PresenceDelta.class);
        } catch (RuntimeException e) {
            log.warn("DocumentSubscriberRegistry: malformed remote delta on {}: {}", topic, e.toString());
            return;
        }
        if (delta.getPodId() == null) return;
        // Ignore our own publishes that loop back.
        if (Objects.equals(delta.getPodId(), clusterService.selfNodeName())) return;

        switch (delta.getAction()) {
            case ADD -> applyRemoteAdd(delta);
            case REMOVE -> applyRemoteRemove(delta);
            case CLEAR_POD -> applyRemoteClearPod(delta);
        }
    }

    private void applyRemoteAdd(PresenceDelta delta) {
        if (delta.getPath() == null || delta.getViewer() == null) return;
        remoteByPath
                .computeIfAbsent(delta.getPath(), p -> new ConcurrentHashMap<>())
                .computeIfAbsent(delta.getPodId(), p -> ConcurrentHashMap.newKeySet())
                .add(delta.getViewer());
        broadcastPresence(delta.getPath());
    }

    private void applyRemoteRemove(PresenceDelta delta) {
        if (delta.getPath() == null || delta.getViewer() == null) return;
        Map<String, Set<DocumentViewer>> perPod = remoteByPath.get(delta.getPath());
        if (perPod == null) return;
        Set<DocumentViewer> viewers = perPod.get(delta.getPodId());
        if (viewers == null) return;
        viewers.removeIf(v -> Objects.equals(v.getEditorId(), delta.getViewer().getEditorId()));
        if (viewers.isEmpty()) perPod.remove(delta.getPodId());
        if (perPod.isEmpty()) {
            remoteByPath.compute(delta.getPath(),
                    (k, v) -> (v == null || v.isEmpty()) ? null : v);
        }
        broadcastPresence(delta.getPath());
    }

    private void applyRemoteClearPod(PresenceDelta delta) {
        Set<String> affected = new HashSet<>();
        for (Map.Entry<String, Map<String, Set<DocumentViewer>>> e : remoteByPath.entrySet()) {
            if (e.getValue().remove(delta.getPodId()) != null) {
                affected.add(e.getKey());
            }
        }
        for (String path : affected) {
            broadcastPresence(path);
        }
    }

    // ─── tiny helpers ───────────────────────────────────────────────────

    private List<Subscriber> allLocalSubscribers() {
        List<Subscriber> out = new ArrayList<>();
        for (Set<Subscriber> subs : byPath.values()) out.addAll(subs);
        return out;
    }

    private @Nullable String pathForViewerOnSession(Subscriber sub, Set<String> paths) {
        // Best-effort — used only for log/audit; correctness doesn't depend
        // on this since CLEAR_POD covers all paths anyway.
        return paths.isEmpty() ? null : paths.iterator().next();
    }
}
