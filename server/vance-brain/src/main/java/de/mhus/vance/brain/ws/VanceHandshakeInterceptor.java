package de.mhus.vance.brain.ws;

import de.mhus.vance.api.ws.ClientContext;
import de.mhus.vance.api.ws.HandshakeHeaders;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.location.LocationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Validates the HTTP upgrade request and prepares the per-connection
 * {@link ConnectionContext}.
 *
 * <p>Authentication is already done one layer up by {@link AccessFilterBase} —
 * the verified {@link VanceJwtClaims} sit in the request attributes. This
 * interceptor:
 * <ul>
 *   <li>reads the WebSocket-specific headers (client type / version),</li>
 *   <li>generates a fresh {@code editorId} and records this pod's IP,</li>
 *   <li>attaches a {@link ConnectionContext} (without a session) to the
 *       handshake attributes so the dispatcher can route frames against it.</li>
 * </ul>
 *
 * <p>The connection starts <em>session-less</em>. Clients explicitly create
 * or resume a session via {@code session.create} / {@code session.resume}
 * WebSocket messages.
 *
 * <p>Error mapping (see {@code specification/client-protokoll-erweiterbarkeit.md}
 * §2.1a):
 * <ul>
 *   <li>missing or invalid JWT → 401</li>
 *   <li>a credential that is not a full account login (integration token,
 *       narrowed OAuth token, script-run token, anything project-pinned) → 403
 *       — see {@link #isAdmissible}</li>
 *   <li>{@code X-Vance-Profile} value violates the shape pattern → 400</li>
 *   <li>missing {@code X-Vance-Client-Version} → 400</li>
 * </ul>
 *
 * <p>Profile is an <em>open string</em> — unknown values are accepted; whether
 * a recipe-profile-block exists for the value is decided later by the recipe
 * resolver (with {@code profiles.default} as fallback). Only the shape is
 * checked here.
 *
 * <p>If the profile is omitted entirely the most restrictive canonical value
 * ({@link Profiles#WEB}) is assumed — this is the default for browser-based
 * clients that do not pass {@code ?profile=}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VanceHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_CONNECTION = "vance.connection";

    private static final Pattern PROFILE_PATTERN = Pattern.compile(Profiles.PATTERN);

    private final LocationService locationService;
    private final ObjectMapper objectMapper;
    private final OAuthWebSocketRevocationService grantRevocation;

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
        if (!isAdmissible(claims)) {
            log.debug(
                    "Handshake refused: {} credential is not a full account login "
                            + "(user='{}' tenant='{}' scp={} pid='{}')",
                    claims.tokenType(),
                    claims.username(),
                    claims.tenantId(),
                    claims.scopeProfiles(),
                    claims.projectId());
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        String rawProfile = firstHeader(request, HandshakeHeaders.PROFILE);
        if (isBlank(rawProfile)) {
            rawProfile = firstQueryParam(request, HandshakeHeaders.PROFILE_PARAM);
        }
        String clientVersion = firstHeader(request, HandshakeHeaders.CLIENT_VERSION);
        if (isBlank(clientVersion)) {
            clientVersion = firstQueryParam(request, HandshakeHeaders.CLIENT_VERSION_PARAM);
        }
        if (isBlank(clientVersion)) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        String profile;
        if (isBlank(rawProfile)) {
            // Spec §2.1a: missing profile defaults to the most restrictive canonical value.
            profile = Profiles.WEB;
        } else if (!PROFILE_PATTERN.matcher(rawProfile).matches()) {
            // Shape-only check — value identity is open. Reject malformed
            // strings to keep them out of logs / metric labels.
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

        ConnectionContext ctx = new ConnectionContext(
                claims.tenantId(),
                claims.username(),
                claims.username(),
                profile,
                clientVersion,
                clientName,
                UUID.randomUUID().toString(),
                locationService.getPodIp());
        ctx.setClientContext(parseClientContext(request));
        if (claims.tokenType() == TokenType.OAUTH) {
            // The socket outlives the access token; remember the grant so a
            // revocation can still reach the open connection.
            ctx.setOauthGrantId(claims.tokenId());
            grantRevocation.track(ctx);
        }
        attributes.put(ATTR_CONNECTION, ctx);
        log.debug(
                "Handshake ok: user='{}' tenant='{}' profile={} clientName='{}' editorId='{}' podIp='{}'",
                claims.username(),
                claims.tenantId(),
                profile,
                clientName,
                ctx.getEditorId(),
                ctx.getPodIp());
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

    /**
     * Whether the credential may open a socket at all — the handshake's own
     * check, independent of what the access filter already refused.
     *
     * <p>Only a full-account login passes: an {@code ACCESS} token (header,
     * {@code ?token=} or the web UI's cookie) or an OAuth token of the
     * {@code account} scope. Everything narrowed is refused — an integration
     * token, a door-bound OAuth token, a script-run token — and so is any
     * credential carrying a project pin: after the upgrade the connection's
     * security context is rebuilt from tenant and user alone
     * ({@code SecurityContextFactory.fromConnection}), so a confinement would
     * not survive the handshake. An OAuth account token additionally needs its
     * grant id, the key its revocation travels by.
     */
    static boolean isAdmissible(VanceJwtClaims claims) {
        if (!claims.isFullAccountCredential()) {
            return false;
        }
        if (claims.projectId() != null && !claims.projectId().isBlank()) {
            return false;
        }
        return claims.tokenType() != TokenType.OAUTH
                || (claims.tokenId() != null && !claims.tokenId().isBlank());
    }

    /**
     * Parses the optional {@link HandshakeHeaders#CLIENT_CONTEXT} header
     * into a {@link ClientContext}. A missing, blank or malformed value
     * yields {@code null} — it must never fail the handshake. Purely
     * informational: CLI clients send it as the
     * {@link HandshakeHeaders#CLIENT_CONTEXT} header, browser-based clients
     * (Facelift desktop app) as the
     * {@link HandshakeHeaders#CLIENT_CONTEXT_PARAM} query parameter.
     */
    private @Nullable ClientContext parseClientContext(ServerHttpRequest request) {
        String raw = firstHeader(request, HandshakeHeaders.CLIENT_CONTEXT);
        if (isBlank(raw)) {
            // Browser-based clients (Facelift desktop app) cannot set custom
            // headers on the WS upgrade — same payload as a query parameter.
            raw = firstQueryParam(request, HandshakeHeaders.CLIENT_CONTEXT_PARAM);
        }
        if (isBlank(raw)) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, ClientContext.class);
        } catch (RuntimeException e) {
            log.debug("Ignoring malformed client context (header or query param): {}", e.getMessage());
            return null;
        }
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
