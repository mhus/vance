package de.mhus.vance.brain.ws;

import de.mhus.vance.api.ws.ClientType;
import de.mhus.vance.api.ws.HandshakeHeaders;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.location.LocationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Validates the HTTP upgrade request and prepares the per-connection
 * {@link ConnectionContext}.
 *
 * <p>Authentication is already done one layer up by {@link AccessFilterBase} —
 * the verified {@link VanceJwtClaims} sit in the request attributes. This
 * interceptor:
 * <ul>
 *   <li>reads the WebSocket-specific headers (client type / version),</li>
 *   <li>generates a fresh {@code connectionId} and records this pod's IP,</li>
 *   <li>attaches a {@link ConnectionContext} (without a session) to the
 *       handshake attributes so the dispatcher can route frames against it.</li>
 * </ul>
 *
 * <p>The connection starts <em>session-less</em>. Clients explicitly create
 * or resume a session via {@code session.create} / {@code session.resume}
 * WebSocket messages.
 *
 * <p>Error mapping (see {@code specification/websocket-protokoll.md} §2):
 * <ul>
 *   <li>missing or invalid JWT → 401</li>
 *   <li>missing / invalid {@code X-Vance-Client-Type} or {@code -Version} → 400</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VanceHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_CONNECTION = "vance.connection";

    private final LocationService locationService;

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        VanceJwtClaims claims = resolveClaims(request);
        if (claims == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String rawClientType = firstHeader(request, HandshakeHeaders.CLIENT_TYPE);
        if (isBlank(rawClientType)) {
            rawClientType = firstQueryParam(request, HandshakeHeaders.CLIENT_TYPE_PARAM);
        }
        String clientVersion = firstHeader(request, HandshakeHeaders.CLIENT_VERSION);
        if (isBlank(clientVersion)) {
            clientVersion = firstQueryParam(request, HandshakeHeaders.CLIENT_VERSION_PARAM);
        }
        if (isBlank(rawClientType) || isBlank(clientVersion)) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        ClientType clientType;
        try {
            clientType = ClientType.fromWire(rawClientType);
        } catch (IllegalArgumentException e) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        ConnectionContext ctx = new ConnectionContext(
                claims.tenantId(),
                claims.username(),
                claims.username(),
                clientType,
                clientVersion,
                UUID.randomUUID().toString(),
                locationService.getPodIp());
        attributes.put(ATTR_CONNECTION, ctx);
        log.debug("Handshake ok: user='{}' tenant='{}' clientType={} connectionId='{}' podIp='{}'",
                claims.username(), claims.tenantId(), clientType,
                ctx.getConnectionId(), ctx.getPodIp());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            @Nullable Exception exception) {
        // No session is bound here anymore — nothing to release.
    }

    private static @Nullable VanceJwtClaims resolveClaims(ServerHttpRequest request) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return null;
        }
        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        Object raw = httpRequest.getAttribute(AccessFilterBase.ATTR_CLAIMS);
        return raw instanceof VanceJwtClaims claims ? claims : null;
    }

    private static @Nullable String firstHeader(ServerHttpRequest request, String name) {
        List<String> values = request.getHeaders().get(name);
        if (values == null || values.isEmpty()) return null;
        return values.get(0);
    }

    private static @Nullable String firstQueryParam(ServerHttpRequest request, String name) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return null;
        }
        return servletRequest.getServletRequest().getParameter(name);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
