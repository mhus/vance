package de.mhus.vance.brain.ws.signals;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SignalFrame;
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
 * Fan-out for the generic ephemeral {@code signals} channel: per-path local
 * subscriber roster + cross-pod Redis pub/sub, mirroring
 * {@link de.mhus.vance.brain.ws.pointers.PointerBroadcaster} but without a
 * roster/leave concept and without sender-skip (signals are server-originated,
 * not echoed from a client). A push to a path with no local subscribers is a
 * no-op — so producers pay nothing when nobody is watching.
 *
 * <p>Signals are fire-and-forget and <b>never touch the database</b>: this is
 * the deliberate alternative to persisting transient run state into the
 * document. Delivery is best-effort; with {@code vance.redis.enabled=false} it
 * works pod-locally only.
 */
@Service
@Slf4j
public class SignalBroadcaster {

    static final String CHANNEL = "signals";

    /** path → ws-session-ids on this pod subscribed to the signal stream. */
    private final Map<String, Set<String>> localSubsByPath = new ConcurrentHashMap<>();

    /** ws-session-id → set of subscribed paths (cleanup index). */
    private final Map<String, Set<String>> bySession = new ConcurrentHashMap<>();

    /** ws-session-id → push target + identity. */
    private final Map<String, LocalSubscriber> bySessionInfo = new ConcurrentHashMap<>();

    private final WebSocketSender sender;
    private final VanceRedisMessagingService redis;
    private final ObjectMapper objectMapper;

    /** Per-process identity used to ignore our own Redis pub/sub echoes. */
    private final String podId = UUID.randomUUID().toString();

    public SignalBroadcaster(WebSocketSender sender,
                             VanceRedisMessagingService redis,
                             ObjectMapper objectMapper) {
        this.sender = sender;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    private record LocalSubscriber(WebSocketSession wsSession, ConnectionContext context) {}

    @PostConstruct
    public void start() {
        redis.subscribeAcrossTenants(CHANNEL, this::onRemote);
        log.debug("SignalBroadcaster: podId={} redis.enabled={}", podId, redis.isEnabled());
    }

    @PreDestroy
    public void stop() {
        redis.unsubscribeAcrossTenants(CHANNEL);
    }

    // ─── subscription lifecycle ─────────────────────────────────────────

    public void subscribe(WebSocketSession wsSession, ConnectionContext ctx, String path) {
        String wsId = wsSession.getId();
        bySession.computeIfAbsent(wsId, k -> ConcurrentHashMap.newKeySet()).add(path);
        bySessionInfo.putIfAbsent(wsId, new LocalSubscriber(wsSession, ctx));
        localSubsByPath.computeIfAbsent(path, k -> ConcurrentHashMap.newKeySet()).add(wsId);
        log.trace("signals.subscribe: ws='{}' user='{}' path='{}'", wsId, ctx.getUserId(), path);
    }

    public void unsubscribe(WebSocketSession wsSession, String path) {
        String wsId = wsSession.getId();
        Set<String> paths = bySession.get(wsId);
        if (paths != null) {
            paths.remove(path);
            if (paths.isEmpty()) {
                bySession.remove(wsId);
                bySessionInfo.remove(wsId);
            }
        }
        Set<String> ids = localSubsByPath.get(path);
        if (ids != null) {
            ids.remove(wsId);
            if (ids.isEmpty()) localSubsByPath.remove(path);
        }
        log.trace("signals.unsubscribe: ws='{}' path='{}'", wsId, path);
    }

    public void unsubscribeAll(WebSocketSession wsSession) {
        String wsId = wsSession.getId();
        Set<String> paths = bySession.remove(wsId);
        bySessionInfo.remove(wsId);
        if (paths == null || paths.isEmpty()) return;
        for (String path : paths) {
            Set<String> ids = localSubsByPath.get(path);
            if (ids != null) {
                ids.remove(wsId);
                if (ids.isEmpty()) localSubsByPath.remove(path);
            }
        }
        log.trace("signals.unsubscribeAll: ws='{}' paths={}", wsId, paths.size());
    }

    public boolean isSubscribed(WebSocketSession wsSession, String path) {
        Set<String> paths = bySession.get(wsSession.getId());
        return paths != null && paths.contains(path);
    }

    public int subscriptionCountOf(WebSocketSession wsSession) {
        Set<String> paths = bySession.get(wsSession.getId());
        return paths == null ? 0 : paths.size();
    }

    // ─── fan-out ────────────────────────────────────────────────────────

    /**
     * Broadcast a signal to all subscribers of {@code frame.path} in
     * {@code tenantId} — local subscribers first, then a Redis publish for peer
     * pods. No-op locally when nobody subscribes the path.
     */
    public void broadcast(String tenantId, SignalFrame frame) {
        broadcastLocal(tenantId, frame);
        try {
            redis.publish(tenantId, CHANNEL, encode(tenantId, frame));
        } catch (RuntimeException ex) {
            log.debug("signals redis publish failed for '{}/{}': {}",
                    tenantId, frame.getPath(), ex.toString());
        }
    }

    private void broadcastLocal(String tenantId, SignalFrame frame) {
        Set<String> wsIds = localSubsByPath.get(frame.getPath());
        if (wsIds == null || wsIds.isEmpty()) return;
        WebSocketEnvelope envelope = WebSocketEnvelope.notification(MessageType.SIGNAL, frame);
        for (String wsId : wsIds) {
            LocalSubscriber recipient = bySessionInfo.get(wsId);
            if (recipient == null) continue;
            // Path strings can collide across tenants → only deliver to the
            // matching tenant (pointers accepts the collision; signals carry
            // run ids, so we scope it).
            if (!Objects.equals(recipient.context().getTenantId(), tenantId)) continue;
            trySend(recipient.wsSession(), envelope, frame.getPath());
        }
    }

    private void trySend(WebSocketSession wsSession, WebSocketEnvelope envelope, String path) {
        try {
            sender.sendOnChannel(wsSession, CHANNEL, envelope);
        } catch (IOException e) {
            log.debug("signals push failed ws='{}' path='{}': {}",
                    wsSession.getId(), path, e.toString());
        }
    }

    // ─── cross-pod receive ──────────────────────────────────────────────

    private void onRemote(String topic, String body) {
        // {podId}|{tenantId}|{base64(json(SignalFrame))}
        String[] parts = body.split("\\|", -1);
        if (parts.length < 3) return;
        if (Objects.equals(parts[0], podId)) return;  // own echo
        String tenantId = parts[1];
        SignalFrame frame = decode(parts[2]);
        if (frame == null || frame.getPath() == null) return;
        if (localSubsByPath.get(frame.getPath()) == null) return;  // nobody local cares
        broadcastLocal(tenantId, frame);
    }

    // ─── wire helpers ───────────────────────────────────────────────────

    private String encode(String tenantId, SignalFrame frame) {
        try {
            String json = objectMapper.writeValueAsString(frame);
            return podId + "|" + tenantId + "|"
                    + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            return podId + "|" + tenantId + "|";
        }
    }

    private @Nullable SignalFrame decode(String b64) {
        if (b64.isEmpty()) return null;
        try {
            byte[] json = Base64.getDecoder().decode(b64);
            return objectMapper.readValue(new String(json, StandardCharsets.UTF_8), SignalFrame.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
