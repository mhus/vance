package de.mhus.vance.brain.ws;

import de.mhus.vance.api.ws.ClientType;
import de.mhus.vance.api.ws.HandshakeHeaders;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Validates the HTTP upgrade request and bootstraps a {@link ClientSession}.
 *
 * Responsibilities (see {@code specification/websocket-protokoll.md} §2):
 * <ul>
 *   <li>reject requests without {@code X-Vance-Client-Type} / {@code X-Vance-Client-Version} (HTTP 400)</li>
 *   <li>create or resume a {@link ClientSession} — resume failures map to 404/403</li>
 *   <li>attach the resolved session to the handshake attributes under {@link #ATTR_SESSION}</li>
 * </ul>
 *
 * Note: JWT signature verification is not yet wired in. For now the {@code Authorization}
 * bearer token (if present) is treated as an opaque user identifier so the full
 * connection flow can be exercised end-to-end. A real {@code JwtAuthenticator}
 * plugs in here once the JWT infrastructure lands.
 */
@Component
public class VanceHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_SESSION = "vance.clientSession";
    public static final String ATTR_RESUMED = "vance.sessionResumed";

    private static final String PLACEHOLDER_USER_ID = "usr_anonymous";

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

        String userId = resolveUserId(request);
        String requestedSessionId = firstHeader(request, HandshakeHeaders.SESSION_ID);

        ClientSessionRegistry.Result result;
        try {
            result = registry.createOrResume(
                    emptyToNull(requestedSessionId),
                    userId,
                    null,
                    null,
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

    private String resolveUserId(ServerHttpRequest request) {
        String authorization = firstHeader(request, HandshakeHeaders.AUTHORIZATION);
        if (authorization == null) {
            return PLACEHOLDER_USER_ID;
        }
        if (!authorization.startsWith(HandshakeHeaders.BEARER_PREFIX)) {
            return PLACEHOLDER_USER_ID;
        }
        String token = authorization.substring(HandshakeHeaders.BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return PLACEHOLDER_USER_ID;
        }
        return "usr_" + Integer.toHexString(token.hashCode());
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
