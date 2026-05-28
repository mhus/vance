package de.mhus.vance.brain.access;

import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Access filter for the Brain.
 *
 * <p>All tenant-scoped endpoints live under {@code /brain/{tenant}/...}. The
 * filter enforces two things uniformly:
 * <ol>
 *   <li>A valid bearer JWT is required (except {@code /actuator/**} and the
 *       token-mint endpoint, which cannot by definition).</li>
 *   <li>The tenant in the URL must match the {@code tid} claim in the JWT —
 *       i.e. a user authenticated for tenant A cannot reach
 *       {@code /brain/B/...}.</li>
 * </ol>
 */
@Component
@Slf4j
public class BrainAccessFilter extends AccessFilterBase {

    /** {@code POST /brain/{tenant}/access/{username}} — open (client has no token yet). */
    private static final Pattern TOKEN_MINT_PATH = Pattern.compile("^/brain/[^/]+/access/[^/]+/?$");

    /**
     * {@code POST /brain/{tenant}/logout} — open. Logout must succeed
     * even when the access cookie has already expired, otherwise users
     * get stuck in a state where the browser still has stale cookies
     * but no way to clear them.
     */
    private static final Pattern LOGOUT_PATH = Pattern.compile("^/brain/[^/]+/logout/?$");

    /** Any tenant-scoped path: captures the tenant name in group 1. */
    private static final Pattern BRAIN_TENANT_PATH = Pattern.compile("^/brain/([^/]+)(?:/.*)?$");

    /**
     * Document-content streaming endpoint. The web UI renders this
     * URL inside {@code <img src>}, {@code <iframe src>} and
     * {@code <a href download>} — none of which can carry an
     * {@code Authorization} header. Allow {@code ?token=<jwt>} as a
     * fallback for GET-only access here, scoped tightly to this
     * route so the security trade-off (token in URL → access logs,
     * referer headers, history) stays minimal.
     */
    private static final Pattern DOCUMENT_CONTENT_PATH =
            Pattern.compile("^/brain/[^/]+/documents/[^/]+/content/?$");

    /**
     * WebSocket upgrade endpoint. The browser {@code WebSocket()} API
     * cannot attach an {@code Authorization} header, so web clients
     * pass the JWT as {@code ?token=}. Same caveat as the document
     * route: token will appear in access logs of the upgrade request.
     * Acceptable here because the JWT is short-lived and the WS
     * connection itself authenticates by socket identity afterwards.
     */
    private static final Pattern WS_UPGRADE_PATH =
            Pattern.compile("^/brain/[^/]+/ws/?$");

    /**
     * External event-trigger endpoint —
     * {@code /brain/{tenant}/event/{project}/{event}}. External
     * callers (webhooks, CI, IoT) don't hold a JWT; auth is performed
     * inside {@link de.mhus.vance.brain.ursaeventtrigger.UrsaEventService}
     * against the YAML-configured bearer token. The filter bypass is
     * scoped tightly to this exact path shape so unrelated
     * {@code /brain/{tenant}/event/...} typos don't accidentally
     * open up.
     */
    private static final Pattern EVENT_TRIGGER_PATH =
            Pattern.compile("^/brain/[^/]+/event/[^/]+/[^/]+/?$");

    /**
     * Office download / callback endpoints called by the ONLYOFFICE /
     * Collabora document server. These don't carry a Vance bearer
     * token — the document server signs the request with the
     * per-tenant {@code office.jwtSecret}, the OfficeController
     * verifies that token itself. Letting them past this filter
     * keeps the global tenant-mismatch check from rejecting them.
     */
    private static final Pattern OFFICE_EXTERNAL_PATH =
            Pattern.compile("^/brain/[^/]+/office/(download|callback)/[^/]+/?$");

    public BrainAccessFilter(JwtService jwtService) {
        super(jwtService);
    }

    @Override
    protected boolean shouldRequireAuthentication(String requestUri, String method) {
        if (requestUri.startsWith("/actuator/")) {
            return false;
        }
        if (requestUri.startsWith("/internal/")) {
            // Pod-internal routes use a shared-secret header instead of a JWT —
            // see InternalAccessFilter. Letting them through here keeps the
            // tenant-mismatch check below from rejecting the no-tenant URL shape.
            return false;
        }
        if (TOKEN_MINT_PATH.matcher(requestUri).matches()) {
            return false;
        }
        if (LOGOUT_PATH.matcher(requestUri).matches()) {
            return false;
        }
        if (EVENT_TRIGGER_PATH.matcher(requestUri).matches()) {
            // External callers don't have a JWT — the UrsaEventService
            // performs its own bearer-token check based on the
            // event's YAML config.
            return false;
        }
        if (OFFICE_EXTERNAL_PATH.matcher(requestUri).matches()) {
            // ONLYOFFICE / Collabora document server callbacks — the
            // OfficeController verifies its own JWT signed with the
            // per-tenant office.jwtSecret.
            return false;
        }
        return true;
    }

    @Override
    protected boolean allowsQueryToken(String requestUri, String method) {
        if (!"GET".equals(method)) {
            return false;
        }
        // Document-content streaming and WebSocket upgrade — both routes
        // can't carry an Authorization header from the browser.
        return DOCUMENT_CONTENT_PATH.matcher(requestUri).matches()
                || WS_UPGRADE_PATH.matcher(requestUri).matches();
    }

    @Override
    protected boolean isClaimsAcceptable(VanceJwtClaims claims, HttpServletRequest request) {
        String uri = request.getRequestURI();
        Matcher matcher = BRAIN_TENANT_PATH.matcher(uri);
        if (!matcher.matches()) {
            // Non-tenant-scoped paths — nothing to cross-check here.
            return true;
        }
        String pathTenant = matcher.group(1);
        if (!pathTenant.equals(claims.tenantId())) {
            log.debug("Tenant mismatch: path='{}' jwt='{}' on {}", pathTenant, claims.tenantId(), uri);
            return false;
        }
        return true;
    }
}
