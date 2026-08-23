package de.mhus.vance.brain.ws.clients;

import de.mhus.vance.api.ws.RemoteClientAnnounce;
import de.mhus.vance.api.ws.RemoteClientInfo;
import de.mhus.vance.api.ws.RemoteClientState;
import de.mhus.vance.shared.redis.VanceRedisMessagingService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Who has a remote-controllable CLI client, and where its WebSocket currently
 * hangs.
 *
 * <p>The roster is a Redis HASH per user — {@code remote:clients:{userId}},
 * field {@code clientId} — with a TTL that a foot heartbeat keeps refreshing.
 * Same shape as the documents-presence roster, and for the same reason: a pod
 * that dies takes no cleanup with it, its entries simply expire.
 *
 * <p><b>The key structure is the authorization.</b> A roster lives under the
 * owning user's id, so a lookup for somebody else's client does not fail a
 * permission check — it finds nothing. That matters because attaching to a foot
 * is effectively shell access to the machine it runs on, and this is the one
 * place where "tenant admin may do anything" must not apply.
 *
 * <p>{@code podId} is stored for diagnostics only. Nothing routes by it: the
 * command channel is keyed by {@code clientId}, which is what makes a foot
 * reconnecting onto a different pod a non-event
 * (planning/foot-remote-control.md §3.2).
 */
@Service
@Slf4j
public class RemoteClientRegistry {

    static final String HASH_SUBKEY_PREFIX = "remote:clients:";

    /**
     * Roster TTL. Three missed heartbeats (foot sends every 30 s) before an
     * entry disappears — long enough that a reconnect blip does not make the
     * client vanish from the list mid-scroll.
     */
    static final Duration ROSTER_TTL = Duration.ofSeconds(100);

    /** wsSession-id → the client bound to it by {@code client-announce}. */
    private final Map<String, LocalClient> byWsSession = new ConcurrentHashMap<>();

    /** clientId → local client (only for feet connected to *this* pod). */
    private final Map<String, LocalClient> byClientId = new ConcurrentHashMap<>();

    private final VanceRedisMessagingService redis;
    private final ObjectMapper objectMapper;

    @Value("${vance.pod.name:local}")
    private String podName = "local";

    public RemoteClientRegistry(VanceRedisMessagingService redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** A foot connected to this pod. */
    public record LocalClient(String clientId,
                              String tenantId,
                              String userId,
                              WebSocketSession wsSession,
                              RemoteClientInfo info) {}

    // ─── registration ───────────────────────────────────────────────────

    /**
     * Binds {@code announce} to the sending WebSocket and writes the roster
     * entry. Re-announcing on the same socket updates in place; announcing the
     * same {@code clientId} from a new socket (the reconnect case) replaces the
     * old binding, which is correct — the process is the same, the transport
     * is not.
     */
    public LocalClient announce(WebSocketSession wsSession,
                                String tenantId,
                                String userId,
                                RemoteClientAnnounce announce) {
        RemoteClientInfo info = RemoteClientInfo.builder()
                .clientId(announce.getClientId())
                .label(announce.getLabel())
                .host(announce.getHost())
                .cwd(announce.getCwd())
                .pid(announce.getPid())
                .version(announce.getVersion())
                .profile(announce.getProfile())
                .lastSeq(announce.getLastSeq())
                .acceptingInput(false)
                .lastSeenAt(Instant.now().toString())
                .podId(podName)
                .build();
        LocalClient client = new LocalClient(
                announce.getClientId(), tenantId, userId, wsSession, info);

        LocalClient previous = byClientId.put(announce.getClientId(), client);
        if (previous != null && !previous.wsSession().getId().equals(wsSession.getId())) {
            byWsSession.remove(previous.wsSession().getId());
        }
        byWsSession.put(wsSession.getId(), client);
        writeRoster(client);
        log.debug("remote client announced: clientId={} user={} tenant={} pod={}",
                announce.getClientId(), userId, tenantId, podName);
        return client;
    }

    /**
     * Applies a heartbeat/state frame. The client is resolved from the sending
     * socket, never from the payload — otherwise any connection could rewrite
     * another client's roster row by claiming its id.
     */
    public @Nullable LocalClient heartbeat(WebSocketSession wsSession, RemoteClientState state) {
        LocalClient client = byWsSession.get(wsSession.getId());
        if (client == null) {
            return null;
        }
        RemoteClientInfo info = client.info();
        info.setSessionId(state.getSessionId());
        info.setProjectId(state.getProjectId());
        info.setUiMode(state.getUiMode());
        info.setBusy(state.isBusy());
        info.setLastSeq(state.getLastSeq());
        info.setAcceptingInput(state.isAcceptingInput());
        info.setInputBlockedReason(state.getInputBlockedReason());
        info.setLastSeenAt(Instant.now().toString());
        writeRoster(client);
        return client;
    }

    /** The client bound to this socket, if it announced one. */
    public @Nullable LocalClient byWsSession(WebSocketSession wsSession) {
        return byWsSession.get(wsSession.getId());
    }

    /** The local foot with this id, or {@code null} when it lives on another pod. */
    public @Nullable LocalClient findLocal(String clientId) {
        return byClientId.get(clientId);
    }

    /** Drops the client bound to a closing socket. */
    public void forget(WebSocketSession wsSession) {
        LocalClient client = byWsSession.remove(wsSession.getId());
        if (client == null) {
            return;
        }
        byClientId.remove(client.clientId(), client);
        try {
            redis.hashDelete(client.tenantId(), hashSubKey(client.userId()), client.clientId());
        } catch (RuntimeException e) {
            log.debug("remote roster cleanup failed for {}: {}", client.clientId(), e.toString());
        }
        log.debug("remote client gone: clientId={}", client.clientId());
    }

    // ─── lookup ─────────────────────────────────────────────────────────

    /**
     * All clients of {@code userId}, newest heartbeat first.
     *
     * <p>Local entries are merged over the Redis view rather than replacing it:
     * with Redis off there is no hash at all and the local map is the whole
     * truth, while with Redis on the local copy is the fresher of the two for
     * feet on this pod.
     */
    public List<RemoteClientInfo> listFor(String tenantId, String userId) {
        Map<String, RemoteClientInfo> merged = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : readRoster(tenantId, userId).entrySet()) {
            RemoteClientInfo info = decode(entry.getValue());
            if (info != null) {
                merged.put(entry.getKey(), info);
            }
        }
        for (LocalClient client : byClientId.values()) {
            if (client.tenantId().equals(tenantId) && client.userId().equals(userId)) {
                merged.put(client.clientId(), client.info());
            }
        }
        List<RemoteClientInfo> out = new ArrayList<>(merged.values());
        out.sort(Comparator.comparing(
                (RemoteClientInfo i) -> i.getLastSeenAt() == null ? "" : i.getLastSeenAt())
                .reversed());
        return out;
    }

    /**
     * Whether {@code userId} owns {@code clientId}. Answered from that user's
     * own roster, so a client of somebody else is simply not found — the check
     * cannot be widened by a role.
     */
    public boolean owns(String tenantId, String userId, String clientId) {
        LocalClient local = byClientId.get(clientId);
        if (local != null) {
            return local.tenantId().equals(tenantId) && local.userId().equals(userId);
        }
        return readRoster(tenantId, userId).containsKey(clientId);
    }

    /** True when the roster spans pods; false means "this pod only" (Redis off). */
    public boolean isCrossPod() {
        return redis.isEnabled();
    }

    // ─── heartbeat ──────────────────────────────────────────────────────

    /**
     * Refreshes the TTL of every roster key this pod contributes to. Without
     * it a busy-but-quiet client would expire mid-session: the foot heartbeat
     * writes its own field, but a key TTL is per key, not per field.
     */
    @Scheduled(
            fixedDelayString = "${vance.remote.roster-heartbeat-ms:30000}",
            initialDelayString = "${vance.remote.roster-heartbeat-ms:30000}")
    public void refreshRosterTtl() {
        for (LocalClient client : byClientId.values()) {
            try {
                redis.hashRefreshTtl(client.tenantId(), hashSubKey(client.userId()), ROSTER_TTL);
            } catch (RuntimeException e) {
                log.debug("remote roster ttl refresh failed: {}", e.toString());
            }
        }
    }

    // ─── redis plumbing ─────────────────────────────────────────────────

    private void writeRoster(LocalClient client) {
        try {
            redis.hashPut(client.tenantId(), hashSubKey(client.userId()),
                    client.clientId(), objectMapper.writeValueAsString(client.info()), ROSTER_TTL);
        } catch (RuntimeException e) {
            log.debug("remote roster write failed for {}: {}", client.clientId(), e.toString());
        }
    }

    private Map<String, String> readRoster(String tenantId, String userId) {
        try {
            return redis.hashGetAll(tenantId, hashSubKey(userId));
        } catch (RuntimeException e) {
            log.debug("remote roster read failed for user {}: {}", userId, e.toString());
            return Map.of();
        }
    }

    private @Nullable RemoteClientInfo decode(String json) {
        try {
            return objectMapper.readValue(json, RemoteClientInfo.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String hashSubKey(String userId) {
        return HASH_SUBKEY_PREFIX + userId;
    }
}
