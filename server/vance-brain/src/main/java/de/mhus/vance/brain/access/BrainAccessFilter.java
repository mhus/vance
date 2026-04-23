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

    /** Any tenant-scoped path: captures the tenant name in group 1. */
    private static final Pattern BRAIN_TENANT_PATH = Pattern.compile("^/brain/([^/]+)(?:/.*)?$");

    public BrainAccessFilter(JwtService jwtService) {
        super(jwtService);
    }

    @Override
    protected boolean shouldRequireAuthentication(String requestUri, String method) {
        if (requestUri.startsWith("/actuator/")) {
            return false;
        }
        if (TOKEN_MINT_PATH.matcher(requestUri).matches()) {
            return false;
        }
        return true;
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
