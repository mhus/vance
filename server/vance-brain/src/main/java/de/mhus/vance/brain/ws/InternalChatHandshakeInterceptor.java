package de.mhus.vance.brain.ws;

import de.mhus.vance.api.ws.HandshakeHeaders;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.brain.workspace.access.InternalAccessFilter;
import de.mhus.vance.shared.location.LocationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Handshake interceptor for the pod-to-pod chat tunnel
 * {@code /internal/{tenant}/ws/chat}.
 *
 * <p>Two gates:
 * <ol>
 *   <li><b>Shared-secret auth</b> — same {@code X-Vance-Internal-Token}
 *       header as {@link InternalAccessFilter} and the engine-bind
 *       interceptor, validated in constant time.</li>
 *   <li><b>Identity forwarding</b> — the face-pod (sending side) forwards
 *       the original user's identity in dedicated headers
 *       ({@code X-Vance-Forwarded-User-Id}, {@code X-Vance-Forwarded-Tenant-Id},
 *       …). This interceptor builds the {@link ConnectionContext} from
 *       those values so the home-pod handler chain sees the connection as
 *       if it had come directly from the user.</li>
 * </ol>
 *
 * <p>The tenant in the URL path must match the forwarded tenant — the path
 * tenant is the additional defense-in-depth from the user's earlier
 * decision that {@code /internal/...} paths carry the tenant explicitly
 * for separation.
 *
 * <p>See {@code planning/live-ws.md} §8 for the tunnel contract.
 */
@Component
@Slf4j
public class InternalChatHandshakeInterceptor implements HandshakeInterceptor {

    /**
     * Forwarded user-id of the original caller (e.g. {@code alice}). The
     * face-pod has already JWT-validated this on its inbound side; the
     * home-pod trusts it because the shared-secret gate above proves the
     * caller is a peer pod.
     */
    public static final String HDR_FORWARDED_USER_ID = "X-Vance-Forwarded-User-Id";

    /** Forwarded display name (falls back to user-id if absent). */
    public static final String HDR_FORWARDED_DISPLAY_NAME = "X-Vance-Forwarded-Display-Name";

    /** Forwarded tenant — must match the URL path {@code {tenant}} segment. */
    public static final String HDR_FORWARDED_TENANT_ID = "X-Vance-Forwarded-Tenant-Id";

    /**
     * Forwarded original client IP (browser / foot host). Optional; used for
     * audit/logging on the home-pod side. Falls back to the face-pod's IP
     * if absent.
     */
    public static final String HDR_FORWARDED_CLIENT_IP = "X-Vance-Forwarded-Client-Ip";

    private static final Pattern PROFILE_PATTERN = Pattern.compile(Profiles.PATTERN);
    private static final Pattern TENANT_PATH_PATTERN =
            Pattern.compile("^/internal/([^/]+)/ws/chat/?$");

    private final byte[] expectedToken;
    private final LocationService locationService;

    public InternalChatHandshakeInterceptor(
            @Value("${vance.internal.token:}") String token,
            LocationService locationService) {
        this.expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        this.locationService = locationService;
        if (this.expectedToken.length == 0) {
            log.warn("vance.internal.token is empty — /internal/{tenant}/ws/chat rejects every caller");
        }
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        if (!sharedSecretMatches(request)) {
            log.debug("Rejecting internal chat handshake — missing/invalid internal token");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String pathTenant = extractPathTenant(request);
        if (pathTenant == null) {
            log.debug("Rejecting internal chat handshake — path does not match expected shape");
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        String forwardedTenant = firstHeader(request, HDR_FORWARDED_TENANT_ID);
        String forwardedUser = firstHeader(request, HDR_FORWARDED_USER_ID);
        if (isBlank(forwardedTenant) || isBlank(forwardedUser)) {
            log.debug("Rejecting internal chat handshake — missing forwarded identity headers");
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        if (!pathTenant.equals(forwardedTenant)) {
            log.debug("Rejecting internal chat handshake — path tenant '{}' differs from forwarded tenant '{}'",
                    pathTenant, forwardedTenant);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        String displayName = firstHeader(request, HDR_FORWARDED_DISPLAY_NAME);
        if (isBlank(displayName)) {
            displayName = forwardedUser;
        }

        String clientVersion = firstHeader(request, HandshakeHeaders.CLIENT_VERSION);
        if (isBlank(clientVersion)) {
            clientVersion = firstQueryParam(request, HandshakeHeaders.CLIENT_VERSION_PARAM);
        }
        if (isBlank(clientVersion)) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        String rawProfile = firstHeader(request, HandshakeHeaders.PROFILE);
        if (isBlank(rawProfile)) {
            rawProfile = firstQueryParam(request, HandshakeHeaders.PROFILE_PARAM);
        }
        String profile;
        if (isBlank(rawProfile)) {
            profile = Profiles.WEB;
        } else if (!PROFILE_PATTERN.matcher(rawProfile).matches()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        } else {
            profile = rawProfile;
        }

        String clientName = firstHeader(request, HandshakeHeaders.CLIENT_NAME);
        if (isBlank(clientName)) {
            clientName = firstQueryParam(request, HandshakeHeaders.CLIENT_NAME_PARAM);
        }
        if (isBlank(clientName)) {
            clientName = null;
        }

        String clientIp = firstHeader(request, HDR_FORWARDED_CLIENT_IP);
        if (isBlank(clientIp)) {
            clientIp = locationService.getPodIp();
        }

        ConnectionContext ctx = new ConnectionContext(
                forwardedTenant,
                forwardedUser,
                displayName,
                profile,
                clientVersion,
                clientName,
                UUID.randomUUID().toString(),
                clientIp);
        attributes.put(VanceHandshakeInterceptor.ATTR_CONNECTION, ctx);
        log.debug("Internal chat handshake ok: user='{}' tenant='{}' profile={} clientName='{}' connectionId='{}'",
                forwardedUser, forwardedTenant, profile, clientName, ctx.getConnectionId());
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

    private boolean sharedSecretMatches(ServerHttpRequest request) {
        if (expectedToken.length == 0) return false;
        List<String> presented = request.getHeaders().get(InternalAccessFilter.HEADER_INTERNAL_TOKEN);
        if (presented == null || presented.isEmpty()) return false;
        String value = presented.get(0);
        if (value == null || value.isEmpty()) return false;
        byte[] presentedBytes = value.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presentedBytes, expectedToken);
    }

    private static @Nullable String extractPathTenant(ServerHttpRequest request) {
        String path = request.getURI().getRawPath();
        if (path == null) return null;
        var matcher = TENANT_PATH_PATTERN.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
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
