package de.mhus.vance.brain.access;

import de.mhus.vance.shared.integration.IntegrationTokenService;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Validates {@code INTEGRATION} JWTs for the brain access filter — the second
 * half of the credential, next to the signature.
 *
 * <p>Four checks, each closing a different hole:
 *
 * <ol>
 *   <li><b>Registry liveness</b> ({@code jti}) — the only thing that makes a
 *       long-lived token revocable at all. Unknown id counts as revoked.</li>
 *   <li><b>Profile exists</b> ({@code scp}) — resolved against the running
 *       code, not against a list copied into the token when it was minted.
 *       A profile that was removed takes its tokens with it.</li>
 *   <li><b>Surface matches</b> — method <em>and</em> path. Without the method
 *       a capture token would reach {@code DELETE} on the same route.</li>
 *   <li><b>Project pinned</b> ({@code pid}) — refused when the profile
 *       requires one and the token carries none. The path alone cannot bound
 *       the project here: endpoints take it as a query parameter, so a
 *       surface-only token would write into every project's app.</li>
 * </ol>
 *
 * <p>The project claim is only <em>checked for presence</em> here; the actual
 * confinement happens in {@code PermissionService}, against the resource the
 * endpoint really touched rather than against a parameter this filter would
 * have to guess the name of.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntegrationTokenAuthService {

    /** Splits {@code /brain/{tenant}/rest...} into tenant and the rest. */
    private static final Pattern TENANT_PATH = Pattern.compile("^/brain/([^/]+)(/.*)?$");

    /**
     * The WebSocket upgrade. Refused outright, ahead of the profile check.
     *
     * <p>Not because a socket is dangerous in itself, but because the
     * confinement does not survive the handshake: after the upgrade the
     * security context is built from {@code ConnectionContext}, which carries
     * only tenant and user, so a project-pinned token would come out the other
     * side <em>unpinned</em>. Nothing declares this surface today, which is
     * exactly why the guard belongs here now — otherwise the first profile to
     * list {@code /ws} would silently hand out an unconfined socket, and the
     * defect would be invisible at the place it was introduced. Opening this
     * properly means teaching {@code ConnectionContext} the confinement first.
     */
    private static final Pattern WS_UPGRADE_PATH = Pattern.compile("^/ws/?$");

    private final IntegrationTokenService tokenService;
    private final IntegrationScopeRegistry profiles;

    public boolean isAcceptable(VanceJwtClaims claims, HttpServletRequest request) {
        String tokenId = claims.tokenId();
        if (tokenId == null || tokenId.isBlank()) {
            log.debug("INTEGRATION token rejected: missing jti claim (user='{}')", claims.username());
            return false;
        }
        if (claims.scopeProfiles().isEmpty()) {
            log.debug("INTEGRATION token rejected: missing scp claim (jti='{}')", tokenId);
            return false;
        }

        // Every named profile must resolve. An unknown one is not skipped: the
        // token was minted to carry a capability that this brain no longer
        // has, and silently serving the remaining ones would answer a
        // different question than the one the credential was issued for.
        List<IntegrationScopeProfile> granted = new ArrayList<>();
        for (String profileId : claims.scopeProfiles()) {
            IntegrationScopeProfile profile = profiles.find(profileId).orElse(null);
            if (profile == null) {
                log.debug("INTEGRATION token rejected: unknown scope profile '{}' (jti='{}')",
                        profileId, tokenId);
                return false;
            }
            granted.add(profile);
        }
        // If *any* carried profile wants a project, the token needs one. The
        // conservative reading on purpose: the alternative is deciding the pin
        // per matched surface, which would make "is this token pinned" depend
        // on which route it happens to be calling.
        if (granted.stream().anyMatch(IntegrationScopeProfile::requiresProject)
                && (claims.projectId() == null || claims.projectId().isBlank())) {
            log.debug("INTEGRATION token rejected: a carried profile requires a project pin "
                    + "(jti='{}')", tokenId);
            return false;
        }

        String tenantPath = tenantPath(request.getRequestURI());
        if (tenantPath == null) {
            // Not a tenant-scoped URL. An integration token has no business
            // anywhere else, and the profiles cannot express such a path.
            log.debug("INTEGRATION token rejected: non-tenant path '{}' (jti='{}')",
                    request.getRequestURI(), tokenId);
            return false;
        }
        if (WS_UPGRADE_PATH.matcher(tenantPath).matches()) {
            log.debug("INTEGRATION token rejected: the WebSocket upgrade cannot carry a "
                    + "credential confinement (jti='{}')", tokenId);
            return false;
        }
        if (granted.stream().noneMatch(p -> covers(p, request.getMethod(), tenantPath))) {
            log.debug("INTEGRATION token rejected: {} does not cover {} {} (jti='{}')",
                    claims.scopeProfiles(), request.getMethod(), tenantPath, tokenId);
            return false;
        }

        // Liveness last: it is the only check that touches the database, so the
        // cheap structural rejections happen first.
        if (!tokenService.isActive(
                tokenId, claims.tenantId(), claims.username(), claims.projectId())) {
            return false;
        }
        return true;
    }

    private static boolean covers(IntegrationScopeProfile profile, String method, String path) {
        for (IntegrationSurface surface : profile.surfaces()) {
            if (surface.matches(method, path)) {
                return true;
            }
        }
        return false;
    }

    /** The part of the URI after {@code /brain/{tenant}}, or {@code null}. */
    private static @Nullable String tenantPath(String requestUri) {
        Matcher matcher = TENANT_PATH.matcher(requestUri);
        if (!matcher.matches()) {
            return null;
        }
        String rest = matcher.group(2);
        return rest == null || rest.isBlank() ? "/" : rest;
    }
}
