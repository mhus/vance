package de.mhus.vance.brain.ws.pointers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.PointerLeaveNotification;
import de.mhus.vance.api.ws.PointerNotification;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
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
 * {@code pointers}-channel state + fan-out. Unlike the {@code documents}
 * channel this keeps <b>no</b> persistent roster: the only server state is
 * the per-connection set of subscribed paths, held in RAM purely to target
 * local fan-out and to emit {@link MessageType#POINTER_LEAVE} on WS close.
 *
 * <p>Two fan-out paths, mirroring {@code DocumentChangedBroadcaster}:
 *
 * <ol>
 *   <li><b>Local:</b> a {@code pointer-move} / leave from a connection on
 *       this pod is pushed straight to the other local subscribers of the
 *       path (sender's own {@code editorId} filtered out).</li>
 *   <li><b>Cross-pod:</b> the same event is published on Redis Pub/Sub
 *       ({@code vance:{tenant}:pointers} / {@code …:pointers.leave});
 *       every pod pattern-subscribes, ignores its own echo, and fans out
 *       to its local subscribers.</li>
 * </ol>
 *
 * <p>Pure Pub/Sub — no Redis HASH, no TTL roster. When
 * {@code vance.redis.enabled=false} the channel works pod-locally only.
 */
@Service
@Slf4j
public class PointerBroadcaster {

    static final String CHANNEL_MOVE = "pointers";
    static final String CHANNEL_LEAVE = "pointers.leave";

    /** path → ws-session-ids on this pod subscribed to the pointer stream. */
    private final Map<String, Set<String>> localSubsByPath = new ConcurrentHashMap<>();

    /** ws-session-id → set of subscribed paths (cleanup + count index). */
    private final Map<String, Set<String>> bySession = new ConcurrentHashMap<>();

    /** ws-session-id → push target + identity. */
    private final Map<String, LocalSubscriber> bySessionInfo = new ConcurrentHashMap<>();

    private final WebSocketSender sender;
    private final VanceRedisMessagingService redis;
    private final ObjectMapper objectMapper;

    /** Per-process identity used to ignore our own Redis pub/sub echoes. */
    private final String podId = UUID.randomUUID().toString();

    public PointerBroadcaster(
            WebSocketSender sender,
            VanceRedisMessagingService redis,
            ObjectMapper objectMapper) {
        this.sender = sender;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    private record LocalSubscriber(WebSocketSession wsSession, ConnectionContext context) {}

    @PostConstruct
    public void start() {
        redis.subscribeAcrossTenants(CHANNEL_MOVE, this::onRemoteMove);
        redis.subscribeAcrossTenants(CHANNEL_LEAVE, this::onRemoteLeave);
        log.debug("PointerBroadcaster: podId={} redis.enabled={}", podId, redis.isEnabled());
    }

    @PreDestroy
    public void stop() {
        redis.unsubscribeAcrossTenants(CHANNEL_MOVE);
        redis.unsubscribeAcrossTenants(CHANNEL_LEAVE);
    }

    // ─── subscription lifecycle ─────────────────────────────────────────

    public void subscribe(WebSocketSession wsSession, ConnectionContext ctx, String path) {
        String wsId = wsSession.getId();
        bySession.computeIfAbsent(wsId, k -> ConcurrentHashMap.newKeySet()).add(path);
        bySessionInfo.putIfAbsent(wsId, new LocalSubscriber(wsSession, ctx));
        localSubsByPath.computeIfAbsent(path, k -> ConcurrentHashMap.newKeySet()).add(wsId);
        log.trace("pointers.subscribe: ws='{}' user='{}' path='{}'", wsId, ctx.getUserId(), path);
    }

    public void unsubscribe(WebSocketSession wsSession, String path) {
        String wsId = wsSession.getId();
        LocalSubscriber sub = bySessionInfo.get(wsId);
        if (!removeLocal(wsId, path)) return;
        if (sub != null) {
            emitLeave(sub.context().getTenantId(), path, sub.context().getEditorId());
        }
        if (isSessionEmpty(wsId)) bySessionInfo.remove(wsId);
        log.trace("pointers.unsubscribe: ws='{}' path='{}'", wsId, path);
    }

    public void unsubscribeAll(WebSocketSession wsSession) {
        String wsId = wsSession.getId();
        Set<String> paths = bySession.remove(wsId);
        LocalSubscriber sub = bySessionInfo.remove(wsId);
        if (paths == null || paths.isEmpty()) return;
        for (String path : paths) {
            Set<String> ids = localSubsByPath.get(path);
            if (ids != null) {
                ids.remove(wsId);
                if (ids.isEmpty()) localSubsByPath.remove(path);
            }
            if (sub != null) {
                emitLeave(sub.context().getTenantId(), path, sub.context().getEditorId());
            }
        }
        log.trace("pointers.unsubscribeAll: ws='{}' paths={}", wsId, paths.size());
    }

    /** {@code true} when {@code wsSession} subscribes to {@code path} on this pod. */
    public boolean isSubscribed(WebSocketSession wsSession, String path) {
        Set<String> paths = bySession.get(wsSession.getId());
        return paths != null && paths.contains(path);
    }

    public int subscriptionCountOf(WebSocketSession wsSession) {
        Set<String> paths = bySession.get(wsSession.getId());
        return paths == null ? 0 : paths.size();
    }

    // ─── move fan-out ───────────────────────────────────────────────────

    /**
     * Fan out a pointer move originating from a local connection: local
     * subscribers first, then a Redis publish for peer pods.
     */
    public void move(ConnectionContext ctx, String path, double x, double y,
            @Nullable Map<String, Object> data) {
        String displayName = ctx.getDisplayName() != null ? ctx.getDisplayName() : ctx.getUserId();
        PointerNotification payload = PointerNotification.builder()
                .path(path)
                .editorId(ctx.getEditorId())
                .userId(ctx.getUserId())
                .displayName(displayName)
                .x(x)
                .y(y)
                .data(data)
                .build();
        broadcastMoveLocal(ctx.getTenantId(), path, ctx.getEditorId(), payload);
        try {
            redis.publish(ctx.getTenantId(), CHANNEL_MOVE,
                    encodeMove(path, ctx.getEditorId(), ctx.getUserId(), displayName, x, y, data));
        } catch (RuntimeException ex) {
            log.debug("pointers move redis publish failed for '{}/{}': {}",
                    ctx.getTenantId(), path, ex.toString());
        }
    }

    private void emitLeave(@Nullable String tenantId, String path, String editorId) {
        broadcastLeaveLocal(tenantId, path, editorId);
        if (tenantId != null) {
            try {
                redis.publish(tenantId, CHANNEL_LEAVE, encodeLeave(path, editorId));
            } catch (RuntimeException ex) {
                log.debug("pointers leave redis publish failed for '{}/{}': {}",
                        tenantId, path, ex.toString());
            }
        }
    }

    private void broadcastMoveLocal(String tenantId, String path,
            @Nullable String senderEditorId, PointerNotification payload) {
        Set<String> wsIds = localSubsByPath.get(path);
        if (wsIds == null || wsIds.isEmpty()) return;
        WebSocketEnvelope envelope = WebSocketEnvelope.notification(MessageType.POINTER, payload);
        for (String wsId : wsIds) {
            LocalSubscriber recipient = bySessionInfo.get(wsId);
            if (recipient == null) continue;
            // Path strings collide across tenants → only deliver to the
            // matching tenant (code-review B2).
            if (!Objects.equals(recipient.context().getTenantId(), tenantId)) continue;
            // Skip the sender's own connection — never echo a pointer back.
            if (senderEditorId != null
                    && Objects.equals(recipient.context().getEditorId(), senderEditorId)) {
                continue;
            }
            trySend(recipient.wsSession(), envelope, path);
        }
    }

    private void broadcastLeaveLocal(@Nullable String tenantId, String path, String editorId) {
        Set<String> wsIds = localSubsByPath.get(path);
        if (wsIds == null || wsIds.isEmpty()) return;
        PointerLeaveNotification payload = PointerLeaveNotification.builder()
                .path(path)
                .editorId(editorId)
                .build();
        WebSocketEnvelope envelope = WebSocketEnvelope.notification(MessageType.POINTER_LEAVE, payload);
        for (String wsId : wsIds) {
            LocalSubscriber recipient = bySessionInfo.get(wsId);
            if (recipient == null) continue;
            // Tenant-scope the fan-out (code-review B2).
            if (!Objects.equals(recipient.context().getTenantId(), tenantId)) continue;
            // The leaver may already be gone from the map; skip it defensively.
            if (Objects.equals(recipient.context().getEditorId(), editorId)) continue;
            trySend(recipient.wsSession(), envelope, path);
        }
    }

    private void trySend(WebSocketSession wsSession, WebSocketEnvelope envelope, String path) {
        try {
            sender.sendOnChannel(wsSession, "pointers", envelope);
        } catch (IOException e) {
            log.debug("pointers push failed ws='{}' path='{}': {}",
                    wsSession.getId(), path, e.toString());
        }
    }

    // ─── cross-pod receive ──────────────────────────────────────────────

    private void onRemoteMove(String topic, String body) {
        // {podId}|{base64(path)}|{editorId}|{x}|{y}|{userId}|{base64(displayName)}|{base64(dataJson)}
        String[] parts = body.split("\\|", -1);
        if (parts.length < 5) return;
        if (Objects.equals(parts[0], podId)) return;  // own echo
        String path = decode(parts[1]);
        if (path == null) return;
        if (localSubsByPath.get(path) == null) return;  // nobody local cares
        String editorId = parts[2];
        double x;
        double y;
        try {
            x = Double.parseDouble(parts[3]);
            y = Double.parseDouble(parts[4]);
        } catch (NumberFormatException e) {
            return;
        }
        String userId = parts.length >= 6 && !parts[5].isEmpty() ? parts[5] : "";
        String displayName = parts.length >= 7 && !parts[6].isEmpty() ? decode(parts[6]) : userId;
        Map<String, Object> data = parts.length >= 8 && !parts[7].isEmpty()
                ? decodeData(parts[7]) : null;
        PointerNotification payload = PointerNotification.builder()
                .path(path)
                .editorId(editorId)
                .userId(userId)
                .displayName(displayName != null ? displayName : userId)
                .x(x)
                .y(y)
                .data(data)
                .build();
        broadcastMoveLocal(VanceRedisMessagingService.tenantFromTopic(topic), path, editorId, payload);
    }

    private void onRemoteLeave(String topic, String body) {
        // {podId}|{base64(path)}|{editorId}
        String[] parts = body.split("\\|", -1);
        if (parts.length < 3) return;
        if (Objects.equals(parts[0], podId)) return;  // own echo
        String path = decode(parts[1]);
        if (path == null) return;
        if (localSubsByPath.get(path) == null) return;
        broadcastLeaveLocal(VanceRedisMessagingService.tenantFromTopic(topic), path, parts[2]);
    }

    // ─── wire helpers ───────────────────────────────────────────────────

    private String encodeMove(String path, String editorId, String userId,
            @Nullable String displayName, double x, double y,
            @Nullable Map<String, Object> data) {
        return podId
                + "|" + b64(path)
                + "|" + editorId
                + "|" + x
                + "|" + y
                + "|" + (userId == null ? "" : userId)
                + "|" + (displayName == null ? "" : b64(displayName))
                + "|" + encodeData(data);
    }

    private String encodeLeave(String path, String editorId) {
        return podId + "|" + b64(path) + "|" + editorId;
    }

    private String encodeData(@Nullable Map<String, Object> data) {
        if (data == null || data.isEmpty()) return "";
        try {
            return b64(objectMapper.writeValueAsString(data));
        } catch (JacksonException e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private @Nullable Map<String, Object> decodeData(String encoded) {
        String json = decode(encoded);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static @Nullable String decode(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

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

    private boolean isSessionEmpty(String wsId) {
        Set<String> paths = bySession.get(wsId);
        return paths == null || paths.isEmpty();
    }

    /** Test hooks. */
    String podIdForTests() {
        return podId;
    }

    Set<String> localSubscribersForTests(String path) {
        Set<String> ids = localSubsByPath.get(path);
        return ids == null ? Collections.emptySet() : Set.copyOf(ids);
    }
}
