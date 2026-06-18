package de.mhus.vance.brain.ws.live;

import de.mhus.vance.api.ws.HandshakeHeaders;
import de.mhus.vance.api.ws.LiveEnvelope;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.workspace.access.InternalAccessFilter;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.InternalChatHandshakeInterceptor;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-external-WS pool of {@link LiveChatTunnel}s that forward session-channel
 * frames from a Face-Pod to the project's home-pod.
 *
 * <p>One tunnel per external Live-WS connection — the external WS attaches to
 * exactly one session in v1, and that session has exactly one home-pod, so a
 * single upstream socket per external WS is sufficient.
 *
 * <p>Tunnel responses (assistant tokens, command replies, notify, …) flow back
 * as raw {@link WebSocketEnvelope}s, get re-wrapped as {@link LiveEnvelope}s
 * with {@code channel="session"} and written to the external WS.
 *
 * <p>{@link MessageType#WELCOME} frames received on the tunnel are dropped —
 * the Face-Pod already sent its own welcome to the user on connect.
 */
@Service
@Slf4j
public class LiveChatTunnelRegistry {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String internalToken;

    private final Map<String, Entry> byExternalWsId = new ConcurrentHashMap<>();

    public LiveChatTunnelRegistry(
            ObjectMapper objectMapper,
            @Value("${vance.internal.token:}") String internalToken) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().build();
        this.internalToken = internalToken == null ? "" : internalToken;
        if (this.internalToken.isEmpty()) {
            log.warn("vance.internal.token is empty — LiveChatTunnel handshakes will be rejected");
        }
    }

    /**
     * Returns a tunnel for the given external WS + target. Opens a new
     * tunnel the first time; reuses the existing one if the endpoint
     * matches; closes-and-reopens if the endpoint changed (rare — session
     * unbinds then binds to a different home-pod).
     */
    public LiveChatTunnel getOrOpen(
            WebSocketSession externalWs,
            ConnectionContext ctx,
            String homePodEndpoint) throws Exception {

        Entry existing = byExternalWsId.get(externalWs.getId());
        if (existing != null && existing.endpoint.equals(homePodEndpoint)
                && existing.tunnel.isOpen()) {
            return existing.tunnel;
        }
        if (existing != null) {
            existing.tunnel.close();
            byExternalWsId.remove(externalWs.getId());
        }

        URI uri = buildUri(ctx, homePodEndpoint);
        Map<String, String> headers = buildHandshakeHeaders(ctx);
        LiveChatTunnel tunnel = new LiveChatTunnel(
                uri,
                headers,
                httpClient,
                objectMapper,
                env -> forwardToExternal(externalWs, env),
                () -> closeFor(externalWs));
        tunnel.open(CONNECT_TIMEOUT);
        byExternalWsId.put(externalWs.getId(), new Entry(homePodEndpoint, tunnel));
        log.debug("LiveChatTunnel established: external='{}' → home='{}'",
                externalWs.getId(), homePodEndpoint);
        return tunnel;
    }

    /** Close the tunnel (if any) bound to this external WS. */
    public void closeFor(WebSocketSession externalWs) {
        Entry removed = byExternalWsId.remove(externalWs.getId());
        if (removed != null) {
            removed.tunnel.close();
        }
    }

    private URI buildUri(ConnectionContext ctx, String homePodEndpoint) {
        String scheme = homePodEndpoint.startsWith("https://") ? "wss" : "ws";
        String hostPort = homePodEndpoint
                .replaceFirst("^https?://", "")
                .replaceFirst("/$", "");
        return URI.create(scheme + "://" + hostPort
                + "/internal/" + ctx.getTenantId() + "/ws/chat"
                + "?" + HandshakeHeaders.PROFILE_PARAM + "=" + ctx.getProfile()
                + "&" + HandshakeHeaders.CLIENT_VERSION_PARAM + "=" + ctx.getClientVersion()
                + (ctx.getClientName() == null
                        ? ""
                        : "&" + HandshakeHeaders.CLIENT_NAME_PARAM + "=" + ctx.getClientName()));
    }

    private Map<String, String> buildHandshakeHeaders(ConnectionContext ctx) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(InternalAccessFilter.HEADER_INTERNAL_TOKEN, internalToken);
        headers.put(InternalChatHandshakeInterceptor.HDR_FORWARDED_TENANT_ID, ctx.getTenantId());
        headers.put(InternalChatHandshakeInterceptor.HDR_FORWARDED_USER_ID, ctx.getUserId());
        if (ctx.getDisplayName() != null) {
            headers.put(InternalChatHandshakeInterceptor.HDR_FORWARDED_DISPLAY_NAME,
                    ctx.getDisplayName());
        }
        if (ctx.getPodIp() != null) {
            headers.put(InternalChatHandshakeInterceptor.HDR_FORWARDED_CLIENT_IP, ctx.getPodIp());
        }
        // Profile + ClientVersion travel as query params (set via buildUri) for
        // symmetry with the external interceptor — both header and query are
        // accepted but the query carries through HttpClient's WebSocketBuilder
        // without ambiguity.
        return headers;
    }

    private void forwardToExternal(WebSocketSession externalWs, WebSocketEnvelope env) {
        // The Face-Pod already sent its own welcome to the user; suppress the
        // duplicate from the home-pod side.
        if (MessageType.WELCOME.equals(env.getType())) return;

        LiveEnvelope live = new LiveEnvelope("session", sessionIdFromEnvelope(env), env);
        try {
            String json = objectMapper.writeValueAsString(live);
            synchronized (externalWs) {
                if (externalWs.isOpen()) {
                    externalWs.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.warn("Tunnel → external WS forward failed: {}", e.toString());
        }
    }

    /**
     * Best-effort: dig a {@code sessionId} out of common reply payload
     * shapes so the LiveEnvelope downstream carries it. Optional —
     * downstream consumers can also rely on the inner WebSocketEnvelope.
     */
    private @Nullable String sessionIdFromEnvelope(WebSocketEnvelope env) {
        Object data = env.getData();
        if (data == null) return null;
        try {
            Map<?, ?> asMap = objectMapper.convertValue(data, Map.class);
            Object sid = asMap.get("sessionId");
            return sid instanceof String s && !s.isBlank() ? s : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record Entry(String endpoint, LiveChatTunnel tunnel) {}
}
