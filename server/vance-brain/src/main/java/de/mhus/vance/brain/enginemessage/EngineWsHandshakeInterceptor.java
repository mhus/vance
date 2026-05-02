package de.mhus.vance.brain.enginemessage;

import de.mhus.vance.brain.workspace.access.InternalAccessFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Handshake interceptor for the {@code /internal/engine-bind} WebSocket.
 * Validates the {@code X-Vance-Internal-Token} header in constant time
 * before letting the upgrade proceed; same secret as
 * {@link InternalAccessFilter} so the cross-pod token rotation story is
 * unified.
 */
@Component
@Slf4j
public class EngineWsHandshakeInterceptor implements HandshakeInterceptor {

    private final byte[] expectedToken;

    public EngineWsHandshakeInterceptor(@Value("${vance.internal.token:}") String token) {
        this.expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (this.expectedToken.length == 0) {
            log.warn("vance.internal.token is empty — /internal/engine-bind will reject every caller");
        }
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        List<String> presented = request.getHeaders().get(InternalAccessFilter.HEADER_INTERNAL_TOKEN);
        if (!matches(presented == null || presented.isEmpty() ? null : presented.get(0))) {
            log.debug("Rejecting engine-bind handshake — missing/invalid internal token");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            @Nullable Exception exception) {
        // no-op
    }

    private boolean matches(@Nullable String presented) {
        if (presented == null || presented.isEmpty() || expectedToken.length == 0) {
            return false;
        }
        byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presentedBytes, expectedToken);
    }
}
