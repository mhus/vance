package de.mhus.vance.brain.ws;

import de.mhus.vance.api.ws.ClientType;
import de.mhus.vance.api.ws.HandshakeHeaders;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Validates the HTTP upgrade request and bootstraps a {@link ClientSession}.
 *
 * <p>Authentication itself is handled one layer up by
 * {@link AccessFilterBase} — if we get here, the bearer JWT has already been
 * verified and the claims are sitting in the request attributes. This
 * interceptor only deals with the WebSocket-specific headers:
 * <ul>
 *   <li>reject requests without {@code X-Vance-Client-Type} / {@code X-Vance-Client-Version} (HTTP 400)</li>
 *   <li>create or resume a {@link ClientSession} — resume failures map to 404/403</li>
 *   <li>attach the resolved session to the handshake attributes under {@link #ATTR_SESSION}</li>
 * </ul>
 *
 * See {@code specification/websocket-protokoll.md} §2 for the wire contract.
 */
@Component
public class VanceHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_SESSION = "vance.clientSession";
    public static final String ATTR_RESUMED = "vance.sessionResumed";

    private final ClientSessionRegistry registry;

    public VanceHandshakeInterceptor(ClientSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        VanceJwtClaims claims = resolveClaims(request);
        if (claims == null) {
            // The access filter should have rejected already — defend in depth.
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String rawClientType = firstHeader(request, HandshakeHeaders.CLIENT_TYPE);
        String clientVersion = firstHeader(request, HandshakeHeaders.CLIENT_VERSION);
        if (rawClientType == null || rawClientType.isBlank()
                || clientVersion == null || clientVersion.isBlank()) {
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

        String requestedSessionId = firstHeader(request, HandshakeHeaders.SESSION_ID);

        ClientSessionRegistry.Result result;
        try {
            result = registry.createOrResume(
                    emptyToNull(requestedSessionId),
                    claims.username(),
                    claims.username(),
                    claims.tenantId(),
                    clientType,
                    clientVersion);
        } catch (ClientSessionRegistry.SessionNotFoundException e) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        } catch (ClientSessionRegistry.SessionAccessException e) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        if (result.resumed() && result.session().getBoundConnection() != null) {
            response.setStatusCode(HttpStatus.CONFLICT);
            return false;
        }

        attributes.put(ATTR_SESSION, result.session());
        attributes.put(ATTR_RESUMED, result.resumed());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            @Nullable Exception exception) {
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
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private static @Nullable String emptyToNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
