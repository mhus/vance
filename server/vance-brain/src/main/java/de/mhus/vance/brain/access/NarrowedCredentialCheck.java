package de.mhus.vance.brain.access;

import de.mhus.vance.shared.jwt.VanceJwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The structural half of every narrowed credential — integration tokens and
 * narrowed OAuth tokens alike: do the carried scope profiles exist, is the
 * project pin present where a profile needs one, and does one of the
 * profiles cover this method and path.
 *
 * <p>One implementation for both credential kinds on purpose. Two copies of
 * "which route may this token reach" would drift, and the drift would be a
 * door one of them leaves open. What differs between the kinds — where
 * liveness is looked up, what else the claims must say — stays with the
 * kind's own auth service.
 *
 * <p>The project claim is only <em>checked for presence</em> here; the actual
 * confinement happens in {@code PermissionService}, against the resource the
 * endpoint really touched. Presence cuts both ways: a profile that needs a pin
 * refuses a token without one, and a tenant-wide profile
 * ({@link IntegrationScopeProfile#requiresProject()} {@code false}) refuses a
 * token with one — its door cannot carry the confinement to where the work
 * happens.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NarrowedCredentialCheck {

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
     * list {@code /ws} would silently hand out an unconfined socket. Opening
     * this properly means teaching {@code ConnectionContext} the confinement
     * first.
     */
    private static final Pattern WS_UPGRADE_PATH = Pattern.compile("^/ws/?$");

    /**
     * The self-revoke endpoint. {@code DELETE /integration-tokens/current}
     * is reachable for any INTEGRATION token, regardless of the profiles it
     * carries — the URL itself is the authority, and the carried profiles are
     * consulted on the next (non-self-revoke) request.
     *
     * <p>This mirrors the OAuth {@code POST revoke}, which every client can
     * call on its own grant. Without it, a stolen backup token could only be
     * killed through an interactive login — the exact failure mode the
     * self-revoke door exists to avoid. Putting the carve-out here rather
     * than on a fake "self-revoke" profile keeps the {@code scp} claim
     * honest: profiles stay a static declaration of what the token was minted
     * to do, not a moving target that grows an entry whenever a new
     * administrative door is needed.
     *
     * <p>Restricted to {@code INTEGRATION}: the endpoint is for credentials
     * that exist to be self-managed, not for access tokens whose revocation
     * happens through the session they belong to.
     */
    private static final String SELF_REVOKE_PATH = "/integration-tokens/current";

    private final IntegrationScopeRegistry profiles;

    /**
     * Whether the narrowing in {@code claims} admits this request.
     *
     * @param kind credential kind for log lines ({@code INTEGRATION}, {@code OAUTH})
     */
    public boolean admits(String kind, VanceJwtClaims claims, HttpServletRequest request) {
        String tokenId = claims.tokenId();

        // Tenant-path lookup is shared between the self-revoke carve-out
        // above and the structural check below — compute once.
        String tenantPath = tenantPath(request.getRequestURI());

        // Self-revoke carve-out (see SELF_REVOKE_PATH). An INTEGRATION token
        // can always kill itself at the documented URL, regardless of the
        // profiles it carries. Computed before the structural checks so the
        // path lookup is shared with the rest of the method below.
        if ("INTEGRATION".equals(kind)
                && "DELETE".equalsIgnoreCase(request.getMethod())
                && SELF_REVOKE_PATH.equals(tenantPath)) {
            return true;
        }
        // Every named profile must resolve. An unknown one is not skipped: the
        // token was minted to carry a capability that this brain no longer
        // has, and silently serving the remaining ones would answer a
        // different question than the one the credential was issued for.
        List<IntegrationScopeProfile> granted = new ArrayList<>();
        for (String profileId : claims.scopeProfiles()) {
            IntegrationScopeProfile profile = profiles.find(profileId).orElse(null);
            if (profile == null) {
                log.debug("{} token rejected: unknown scope profile '{}' (jti='{}')", kind, profileId, tokenId);
                return false;
            }
            granted.add(profile);
        }
        if (granted.isEmpty()) {
            log.debug("{} token rejected: no scope profile (jti='{}')", kind, tokenId);
            return false;
        }
        // If *any* carried profile wants a project, the token needs one. The
        // conservative reading on purpose: the alternative is deciding the pin
        // per matched surface, which would make "is this token pinned" depend
        // on which route it happens to be calling.
        if (granted.stream().anyMatch(IntegrationScopeProfile::requiresProject)
                && (claims.projectId() == null || claims.projectId().isBlank())) {
            log.debug("{} token rejected: a carried profile requires a project pin (jti='{}')", kind, tokenId);
            return false;
        }
        // The mirror image: a tenant-wide profile must not carry a pin. Its
        // doors (the global MCP door, the agents door) hand the request to
        // code that rebuilds an unconfined security context per tool call, so
        // the pin would be a promise nobody downstream keeps. The mint refuses
        // such a token; this refuses one that got minted anyway.
        if (granted.stream().anyMatch(p -> !p.requiresProject())
                && claims.projectId() != null
                && !claims.projectId().isBlank()) {
            log.debug(
                    "{} token rejected: a carried profile is tenant-wide and cannot honour the project pin '{}'"
                            + " (jti='{}')",
                    kind,
                    claims.projectId(),
                    tokenId);
            return false;
        }

        if (tenantPath == null) {
            // Not a tenant-scoped URL. A narrowed token has no business
            // anywhere else, and the profiles cannot express such a path.
            log.debug("{} token rejected: non-tenant path '{}' (jti='{}')", kind, request.getRequestURI(), tokenId);
            return false;
        }
        if (WS_UPGRADE_PATH.matcher(tenantPath).matches()) {
            log.debug(
                    "{} token rejected: the WebSocket upgrade cannot carry a credential confinement (jti='{}')",
                    kind,
                    tokenId);
            return false;
        }
        if (granted.stream().noneMatch(p -> covers(p, request.getMethod(), tenantPath))) {
            log.debug(
                    "{} token rejected: {} does not cover {} {} (jti='{}')",
                    kind,
                    claims.scopeProfiles(),
                    request.getMethod(),
                    tenantPath,
                    tokenId);
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
