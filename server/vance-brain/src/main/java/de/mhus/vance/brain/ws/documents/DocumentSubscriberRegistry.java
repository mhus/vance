package de.mhus.vance.brain.ws.documents;

import de.mhus.vance.api.ws.DocumentPresenceNotification;
import de.mhus.vance.api.ws.DocumentViewer;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

/**
 * In-memory per-pod registry of {@code documents}-channel subscribers.
 *
 * <p>Tracks for each document path the set of WebSocket connections that
 * have subscribed to its presence list. On each membership change, fans
 * out a {@link DocumentPresenceNotification} push to all current
 * subscribers — each receiver gets a list with their own
 * {@code editorId} filtered out so a client never sees itself.
 *
 * <p>v1 scope is **per-pod**: subscribers on Pod A and Pod B do not see
 * each other. Cross-pod aggregation arrives with the
 * {@code documents.changed}-push follow-up step, which will introduce a
 * cluster-fan-out path that presence can ride on.
 *
 * <p>Cleanup happens via {@link #unsubscribeAll(WebSocketSession)} from
 * {@code LiveWebSocketHandler.afterConnectionClosed}. Stale entries from
 * a missed unsubscribe survive until either the WS is closed (typical) or
 * the client reconnects and re-syncs its desired set.
 *
 * <p>See {@code planning/document-presence.md} §5.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentSubscriberRegistry {

    /** Single-document subscriber entry: who is subscribed and how to reach them. */
    private record Subscriber(
            WebSocketSession wsSession,
            String editorId,
            String userId,
            @Nullable String displayName) {

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

    private final WebSocketSender sender;

    /**
     * Add a subscription for {@code path} on this WebSocket. Idempotent —
     * re-subscribing the same WS to the same path is a no-op but still
     * (re-)triggers a presence broadcast so the client gets a fresh
     * roster (useful after reconnect-resubscribe).
     */
    public void subscribe(WebSocketSession wsSession, ConnectionContext ctx, String path) {
        Subscriber subscriber = new Subscriber(
                wsSession, ctx.getConnectionId(), ctx.getUserId(), ctx.getDisplayName());
        boolean added = byPath
                .computeIfAbsent(path, p -> ConcurrentHashMap.newKeySet())
                .add(subscriber);
        if (added) {
            bySession
                    .computeIfAbsent(wsSession.getId(), s -> ConcurrentHashMap.newKeySet())
                    .add(path);
            log.debug("documents.subscribe: ws='{}' user='{}' path='{}'",
                    wsSession.getId(), ctx.getUserId(), path);
        }
        broadcastPresence(path);
    }

    /**
     * Drop the subscription for {@code path} on this WebSocket. No-op if
     * not subscribed. Broadcasts presence to the remaining subscribers.
     */
    public void unsubscribe(WebSocketSession wsSession, String path) {
        boolean removed = removeSubscriberFromPath(wsSession, path);
        if (removed) {
            log.debug("documents.unsubscribe: ws='{}' path='{}'", wsSession.getId(), path);
            broadcastPresence(path);
        }
    }

    /**
     * Drop all subscriptions for this WebSocket. Used by the WS-close hook
     * — see {@code LiveWebSocketHandler.afterConnectionClosed}. Broadcasts
     * presence on every affected path.
     */
    public void unsubscribeAll(WebSocketSession wsSession) {
        Set<String> paths = bySession.remove(wsSession.getId());
        if (paths == null || paths.isEmpty()) return;
        for (String path : paths) {
            removeSubscriberFromPath(wsSession, path);
        }
        for (String path : paths) {
            broadcastPresence(path);
        }
        log.debug("documents.unsubscribeAll: ws='{}' paths={}", wsSession.getId(), paths.size());
    }

    /**
     * Read-only view of the paths a given WebSocket is currently
     * subscribed to. Used by the channel-handler to enforce the
     * per-connection subscription cap.
     */
    public Set<String> pathsOf(WebSocketSession wsSession) {
        Set<String> paths = bySession.get(wsSession.getId());
        return paths == null ? Collections.emptySet() : Set.copyOf(paths);
    }

    /**
     * Read-only view of the current viewers for a path — used by tests
     * and (eventually) by debug/admin endpoints. Not part of the wire
     * protocol.
     */
    public List<DocumentViewer> viewersOf(String path) {
        Set<Subscriber> subs = byPath.get(path);
        if (subs == null || subs.isEmpty()) return Collections.emptyList();
        List<DocumentViewer> viewers = new ArrayList<>(subs.size());
        for (Subscriber s : subs) viewers.add(s.asViewer());
        return viewers;
    }

    // ─── internals ──────────────────────────────────────────────────────

    private boolean removeSubscriberFromPath(WebSocketSession wsSession, String path) {
        Set<Subscriber> subs = byPath.get(path);
        if (subs == null) return false;
        boolean removed = subs.removeIf(s -> Objects.equals(s.wsSession.getId(), wsSession.getId()));
        if (subs.isEmpty()) {
            // Atomically drop the empty set; race-safe via compute.
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

    /**
     * Per-recipient presence broadcast. Each subscriber on the path gets
     * a viewer list with their own {@code editorId} filtered out (so they
     * never see themselves). Same-user-other-tab entries stay visible —
     * that's the intended "you have this open elsewhere"-awareness.
     */
    private void broadcastPresence(String path) {
        Set<Subscriber> subs = byPath.get(path);
        if (subs == null || subs.isEmpty()) return;
        // Snapshot the viewers once; per-recipient we just filter one entry.
        List<DocumentViewer> allViewers = new ArrayList<>(subs.size());
        Set<Subscriber> snapshot = new HashSet<>(subs);
        for (Subscriber s : snapshot) allViewers.add(s.asViewer());

        for (Subscriber recipient : snapshot) {
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
}
