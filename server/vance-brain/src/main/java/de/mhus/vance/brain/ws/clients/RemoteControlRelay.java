package de.mhus.vance.brain.ws.clients;

import de.mhus.vance.api.ws.LiveChannels;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Moves remote-control frames between a CLI client and its watchers, wherever
 * either of them is connected.
 *
 * <p>Both directions are Redis pub/sub on a channel keyed by {@code clientId}.
 * That is the whole multi-pod answer: the publishing pod never learns where the
 * target is, and the subscribing pod is by definition the one holding it. No
 * pod-to-pod tunnel, no directed forward, and — because the key contains no pod
 * — nothing to re-address when a foot reconnects somewhere else.
 *
 * <p>Delivery is fire-and-forget. There is deliberately no acknowledgement
 * across pods: a command published into a reconnect gap is dropped, and the
 * watcher notices because the client shows offline and its line never appears.
 * The alternative — queueing — would deliver an answer to a permission prompt
 * that expired minutes ago (planning/foot-remote-control.md §3.5).
 */
@Service
@Slf4j
public class RemoteControlRelay {

    static final String CHANNEL = "remote-control";

    /** Direction marker on the wire. */
    static final String TO_CLIENT = "c";
    static final String TO_WATCHERS = "w";

    /** clientId → watcher connections on this pod. */
    private final Map<String, Set<String>> watchersByClient = new ConcurrentHashMap<>();

    /** ws-session-id → clientIds this connection watches (cleanup index). */
    private final Map<String, Set<String>> clientsByWatcher = new ConcurrentHashMap<>();

    /** ws-session-id → the actual socket + identity of a watcher. */
    private final Map<String, Watcher> watcherInfo = new ConcurrentHashMap<>();

    private final WebSocketSender sender;
    private final VanceRedisMessagingService redis;
    private final ObjectMapper objectMapper;
    private final RemoteClientRegistry registry;

    /** Per-process identity, used only to ignore our own Redis echo. */
    private final String podId = UUID.randomUUID().toString();

    public RemoteControlRelay(WebSocketSender sender,
                              VanceRedisMessagingService redis,
                              ObjectMapper objectMapper,
                              RemoteClientRegistry registry) {
        this.sender = sender;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    private record Watcher(WebSocketSession wsSession, String tenantId, String userId,
                           String editorId) {}

    @PostConstruct
    public void start() {
        redis.subscribeAcrossTenants(CHANNEL, this::onRemote);
        log.debug("RemoteControlRelay: podId={} redis.enabled={}", podId, redis.isEnabled());
    }

    @PreDestroy
    public void stop() {
        redis.unsubscribeAcrossTenants(CHANNEL);
    }

    // ─── watcher bookkeeping ────────────────────────────────────────────

    public void attachWatcher(WebSocketSession wsSession, ConnectionContext ctx, String clientId) {
        String wsId = wsSession.getId();
        watchersByClient.computeIfAbsent(clientId, k -> ConcurrentHashMap.newKeySet()).add(wsId);
        clientsByWatcher.computeIfAbsent(wsId, k -> ConcurrentHashMap.newKeySet()).add(clientId);
        watcherInfo.putIfAbsent(wsId,
                new Watcher(wsSession, ctx.getTenantId(), ctx.getUserId(), ctx.getEditorId()));
        log.trace("remote watcher attached: ws={} client={}", wsId, clientId);
    }

    public void detachWatcher(WebSocketSession wsSession, String clientId) {
        String wsId = wsSession.getId();
        Set<String> clients = clientsByWatcher.get(wsId);
        if (clients != null) {
            clients.remove(clientId);
            if (clients.isEmpty()) {
                clientsByWatcher.remove(wsId);
                watcherInfo.remove(wsId);
            }
        }
        removeFromClient(clientId, wsId);
    }

    /**
     * Drops every attachment of a closing connection and tells each client it
     * lost a watcher, so a foot that nobody watches any more stops streaming
     * instead of publishing into the void.
     */
    public void detachAll(WebSocketSession wsSession) {
        String wsId = wsSession.getId();
        Watcher watcher = watcherInfo.remove(wsId);
        Set<String> clients = clientsByWatcher.remove(wsId);
        if (clients == null || clients.isEmpty()) {
            return;
        }
        for (String clientId : clients) {
            removeFromClient(clientId, wsId);
            // Tell the client this particular watcher is gone even when others
            // remain: it keeps its own set and must not be left holding a
            // phantom entry that keeps the stream alive forever.
            if (watcher != null) {
                sendDetachToClient(watcher.tenantId(), watcher.userId(), clientId,
                        watcher.editorId());
            }
        }
    }

    private void removeFromClient(String clientId, String wsId) {
        Set<String> ids = watchersByClient.get(clientId);
        if (ids != null) {
            ids.remove(wsId);
            if (ids.isEmpty()) {
                watchersByClient.remove(clientId);
            }
        }
    }

    private boolean hasWatchers(String clientId) {
        Set<String> ids = watchersByClient.get(clientId);
        return ids != null && !ids.isEmpty();
    }

    private void sendDetachToClient(String tenantId, String userId, String clientId,
                                    String watcherId) {
        WebSocketEnvelope detach = WebSocketEnvelope.notification(
                de.mhus.vance.api.ws.MessageType.CLIENT_DETACH,
                de.mhus.vance.api.ws.RemoteAttachRequest.builder()
                        .clientId(clientId).watcherId(watcherId).build());
        toClient(tenantId, userId, clientId, detach);
    }

    // ─── routing ────────────────────────────────────────────────────────

    /** Deliver a frame to the CLI client with {@code clientId}, wherever it hangs. */
    public void toClient(String tenantId, String userId, String clientId, WebSocketEnvelope envelope) {
        boolean deliveredLocally = deliverToLocalClient(tenantId, userId, clientId, envelope);
        // Publish regardless of a local hit: the authoritative holder may be a
        // peer pod that took the client over after a reconnect, and a stale
        // local entry must not silently swallow the command.
        publish(TO_CLIENT, tenantId, userId, clientId, envelope);
        if (!deliveredLocally && !redis.isEnabled()) {
            log.debug("remote command for {} undeliverable — client not on this pod and Redis is off",
                    clientId);
        }
    }

    /** Deliver a frame to everyone watching {@code clientId}, wherever they are. */
    public void toWatchers(String tenantId, String userId, String clientId, WebSocketEnvelope envelope) {
        deliverToLocalWatchers(tenantId, clientId, envelope);
        publish(TO_WATCHERS, tenantId, userId, clientId, envelope);
    }

    /**
     * Delivers to the local client with {@code clientId}, but only when it
     * belongs to the tenant and user the command was authorized for.
     *
     * <p>The ownership gate sits in the channel handler; this is the second
     * fence, and it matters most on the cross-pod path, where the frame arrives
     * as bytes on a shared Redis channel rather than from a handler that just
     * checked something.
     */
    private boolean deliverToLocalClient(String tenantId, String userId, String clientId,
                                         WebSocketEnvelope envelope) {
        RemoteClientRegistry.LocalClient client = registry.findLocal(clientId);
        if (client == null) {
            return false;
        }
        if (!Objects.equals(client.tenantId(), tenantId)
                || !Objects.equals(client.userId(), userId)) {
            log.debug("remote-control drop: client '{}' is not owned by {}/{}",
                    clientId, tenantId, userId);
            return false;
        }
        return trySend(client.wsSession(), envelope, clientId);
    }

    private void deliverToLocalWatchers(String tenantId, String clientId, WebSocketEnvelope envelope) {
        Set<String> wsIds = watchersByClient.get(clientId);
        if (wsIds == null || wsIds.isEmpty()) {
            return;
        }
        for (String wsId : wsIds) {
            Watcher watcher = watcherInfo.get(wsId);
            if (watcher == null) continue;
            // clientIds are random enough not to collide, but the roster is
            // tenant-scoped and so is delivery.
            if (!Objects.equals(watcher.tenantId(), tenantId)) continue;
            trySend(watcher.wsSession(), envelope, clientId);
        }
    }

    private boolean trySend(WebSocketSession wsSession, WebSocketEnvelope envelope, String clientId) {
        try {
            sender.sendOnChannel(wsSession, LiveChannels.CLIENTS, envelope);
            return true;
        } catch (IOException e) {
            log.debug("remote-control push failed ws='{}' client='{}': {}",
                    wsSession.getId(), clientId, e.toString());
            return false;
        }
    }

    // ─── cross-pod ──────────────────────────────────────────────────────

    private void publish(String direction, String tenantId, String userId, String clientId,
                         WebSocketEnvelope envelope) {
        if (!redis.isEnabled()) {
            return;
        }
        try {
            redis.publish(tenantId, CHANNEL, encode(direction, tenantId, userId, clientId, envelope));
        } catch (RuntimeException e) {
            log.debug("remote-control redis publish failed for '{}': {}", clientId, e.toString());
        }
    }

    private void onRemote(String topic, String body) {
        // {podId}|{direction}|{tenantId}|{userId}|{clientId}|{base64(json(envelope))}
        String[] parts = body.split("\\|", -1);
        if (parts.length < 6) return;
        if (Objects.equals(parts[0], podId)) return;  // own echo
        String direction = parts[1];
        String tenantId = parts[2];
        String userId = parts[3];
        String clientId = parts[4];
        WebSocketEnvelope envelope = decode(parts[5]);
        if (envelope == null) return;
        if (TO_CLIENT.equals(direction)) {
            deliverToLocalClient(tenantId, userId, clientId, envelope);
        } else if (TO_WATCHERS.equals(direction)) {
            deliverToLocalWatchers(tenantId, clientId, envelope);
        }
    }

    private String encode(String direction, String tenantId, String userId, String clientId,
                          WebSocketEnvelope envelope) {
        String prefix = podId + "|" + direction + "|" + tenantId + "|" + userId + "|" + clientId + "|";
        try {
            String json = objectMapper.writeValueAsString(envelope);
            return prefix + Base64.getEncoder()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            return prefix;
        }
    }

    private @Nullable WebSocketEnvelope decode(String b64) {
        if (b64.isEmpty()) return null;
        try {
            byte[] json = Base64.getDecoder().decode(b64);
            return objectMapper.readValue(
                    new String(json, StandardCharsets.UTF_8), WebSocketEnvelope.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
