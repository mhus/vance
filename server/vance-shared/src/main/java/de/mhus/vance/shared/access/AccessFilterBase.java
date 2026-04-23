package de.mhus.vance.shared.access;

import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Base servlet filter that verifies {@code Authorization: Bearer &lt;jwt&gt;} on
 * every request and attaches the verified claims as request attributes.
 *
 * <p>Authentication for vance is JWT-only — no cookies. Clients always send the
 * bearer header, whether they are hitting REST or upgrading to WebSocket. For
 * the WebSocket endpoint this means the filter runs during the HTTP upgrade
 * request, before the handshake interceptor; the interceptor simply reads
 * {@link #ATTR_CLAIMS} from the request.
 *
 * <p>Concrete subclasses decide which paths require authentication by
 * implementing {@link #shouldRequireAuthentication(String, String)}.
 *
 * <p>Request attributes set on success:
 * <ul>
 *   <li>{@link #ATTR_AUTHENTICATED} — {@code Boolean}</li>
 *   <li>{@link #ATTR_USERNAME}      — {@code String}, from {@code sub}</li>
 *   <li>{@link #ATTR_TENANT_ID}     — {@code String}, from {@code tid}</li>
 *   <li>{@link #ATTR_CLAIMS}        — {@link VanceJwtClaims}</li>
 * </ul>
 */
@Slf4j
public abstract class AccessFilterBase extends OncePerRequestFilter {

    public static final String ATTR_AUTHENTICATED = "vance.access.authenticated";
    public static final String ATTR_USERNAME = "vance.access.username";
    public static final String ATTR_TENANT_ID = "vance.access.tenantId";
    public static final String ATTR_CLAIMS = "vance.access.claims";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    protected AccessFilterBase(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * Whether the given request path/method requires a valid bearer token.
     * Return {@code false} to let the request through unauthenticated
     * (e.g. for {@code /actuator/**} or a future {@code /api/auth/login}).
     */
    protected abstract boolean shouldRequireAuthentication(String requestUri, String method);

    /**
     * Hook for subclass-specific claim validation after signature has been verified.
     * Default accepts everything.
     */
    protected boolean isClaimsAcceptable(VanceJwtClaims claims) {
        return true;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if ("OPTIONS".equals(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        VanceJwtClaims claims = resolveClaims(request);
        boolean authenticated = claims != null;

        if (authenticated) {
            request.setAttribute(ATTR_AUTHENTICATED, Boolean.TRUE);
            request.setAttribute(ATTR_USERNAME, claims.username());
            request.setAttribute(ATTR_TENANT_ID, claims.tenantId());
            request.setAttribute(ATTR_CLAIMS, claims);
        } else {
            request.setAttribute(ATTR_AUTHENTICATED, Boolean.FALSE);
        }

        if (!authenticated && shouldRequireAuthentication(request.getRequestURI(), request.getMethod())) {
            log.debug("Rejecting {} {} — no valid bearer token", request.getMethod(), request.getRequestURI());
            writeUnauthorized(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private @Nullable VanceJwtClaims resolveClaims(HttpServletRequest request) {
        String token = extractBearer(request);
        if (token == null) {
            return null;
        }
        VanceJwtClaims claims = jwtService.validateToken(token).orElse(null);
        if (claims == null) {
            log.debug("Bearer token did not pass JWT verification for {}", request.getRequestURI());
            return null;
        }
        if (!isClaimsAcceptable(claims)) {
            log.debug("Claims rejected by subclass for {} (user='{}' tenant='{}')",
                    request.getRequestURI(), claims.username(), claims.tenantId());
            return null;
        }
        return claims;
    }

    private static @Nullable String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("text/plain; charset=UTF-8");
        response.getWriter().write("401 Unauthorized\n");
    }
}
