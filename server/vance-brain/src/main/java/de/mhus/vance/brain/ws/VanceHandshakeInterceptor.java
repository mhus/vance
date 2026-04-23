package de.mhus.vance.brain.ws;

import de.mhus.vance.api.ws.ClientType;
import de.mhus.vance.api.ws.HandshakeHeaders;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.location.LocationService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.session.SessionStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Validates the HTTP upgrade request and resolves or creates the persistent
 * {@link SessionDocument}.
 *
 * <p>Authentication is already done one layer up by {@link AccessFilterBase} —
 * the verified {@link VanceJwtClaims} sit in the request attributes. This
 * interceptor:
 * <ul>
 *   <li>reads the WebSocket-specific headers (client type / version / optional session-resume id),</li>
 *   <li>creates or resumes the {@link SessionDocument} via {@link SessionService},</li>
 *   <li>atomically acquires the connection lock via
 *       {@link SessionService#tryBind(String, String, String)} — stamps the row
 *       with this pod's IP and a freshly generated {@code connectionId},</li>
 *   <li>attaches a {@link ClientSession} wrapper to the handshake attributes so the
 *       WebSocket handler can dispatch on it.</li>
 * </ul>
 *
 * <p>Error mapping (see {@code specification/websocket-protokoll.md} §2):
 * <ul>
 *   <li>missing/invalid {@code X-Vance-Client-Type}/{@code -Version} → 400</li>
 *   <li>session-resume id unknown or already {@link SessionStatus#CLOSED} → 404</li>
 *   <li>session-resume id belongs to another user → 403</li>
 *   <li>session is already bound to another connection → 409</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VanceHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_SESSION = "vance.clientSession";
    public static final String ATTR_RESUMED = "vance.sessionResumed";

    private final SessionService sessionService;
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
        String clientVersion = firstHeader(request, HandshakeHeaders.CLIENT_VERSION);
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

        String requestedSessionId = emptyToNull(firstHeader(request, HandshakeHeaders.SESSION_ID));
        SessionDocument document;
        boolean resumed;

        if (requestedSessionId == null) {
            document = sessionService.create(
                    claims.tenantId(),
                    claims.username(),
                    claims.username(),
                    clientType,
                    clientVersion);
            resumed = false;
        } else {
            Optional<SessionDocument> existing = sessionService.findBySessionId(requestedSessionId);
            if (existing.isEmpty() || existing.get().getStatus() != SessionStatus.OPEN) {
                response.setStatusCode(HttpStatus.NOT_FOUND);
                return false;
            }
            SessionDocument doc = existing.get();
            if (!doc.getUserId().equals(claims.username())
                    || !doc.getTenantId().equals(claims.tenantId())) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            document = doc;
            resumed = true;
        }

        String connectionId = UUID.randomUUID().toString();
        String podIp = locationService.getPodIp();
        boolean bound = sessionService.tryBind(document.getSessionId(), connectionId, podIp);
        if (!bound) {
            // Another connection still owns the lock — signal conflict, but only for resumes.
            // A fresh session we just created ourselves cannot possibly collide.
            if (!resumed) {
                log.warn("Freshly created session '{}' failed to bind — shouldn't happen",
                        document.getSessionId());
            }
            response.setStatusCode(HttpStatus.CONFLICT);
            return false;
        }

        ClientSession clientSession = new ClientSession(document, connectionId);
        attributes.put(ATTR_SESSION, clientSession);
        attributes.put(ATTR_RESUMED, resumed);
        log.debug("Handshake ok: sessionId='{}' user='{}' tenant='{}' resumed={} podIp='{}'",
                document.getSessionId(), claims.username(), claims.tenantId(), resumed, podIp);
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            @Nullable Exception exception) {
        // If the upgrade itself failed after beforeHandshake returned true,
        // release the bind we just acquired so another attempt can proceed.
        if (exception == null) return;
        Object attr = request.getHeaders();
        if (attr == null) return;
        // We don't have the attributes map here — the handler's afterConnectionClosed
        // is the primary unbind path. This branch catches the rare case where the
        // upgrade fails before the handler is invoked; the bind will be cleared by
        // the idle-session cleanup job (future) if not unbound otherwise.
        log.warn("Handshake failed after bind — unbind will be deferred to cleanup", exception);
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

    private static @Nullable String emptyToNull(@Nullable String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
