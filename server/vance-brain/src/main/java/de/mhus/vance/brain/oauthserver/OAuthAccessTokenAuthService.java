package de.mhus.vance.brain.oauthserver;

import de.mhus.vance.brain.access.NarrowedCredentialCheck;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Validates {@code OAUTH} access tokens for the brain access filter, after the
 * signature and the tenant check.
 *
 * <ul>
 *   <li><b>Narrowed</b> ({@code scp} present): the same structural check as an
 *       integration token ({@link NarrowedCredentialCheck}), plus the audience
 *       — the token must have been issued for exactly the door the request
 *       addresses (RFC 8707; the MCP specification makes this a MUST).</li>
 *   <li><b>Account</b> (no {@code scp}): a full-account credential like an
 *       ACCESS token — only first-party clients ever receive one.</li>
 * </ul>
 * Both end with the grant liveness check, so revoking a grant stops its
 * tokens within the cache window.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthAccessTokenAuthService {

    private final NarrowedCredentialCheck narrowedCheck;
    private final OAuthServerGrantService grantService;
    private final OAuthServerSettings settings;
    private final OAuthDoors doors;

    public boolean isAcceptable(VanceJwtClaims claims, HttpServletRequest request) {
        String grantId = claims.tokenId();
        if (grantId == null || grantId.isBlank()) {
            log.debug("OAUTH token rejected: missing jti (user='{}')", claims.username());
            return false;
        }
        if (!settings.enabled()) {
            log.debug("OAUTH token rejected: OAuth server disabled");
            return false;
        }
        if (!claims.scopeProfiles().isEmpty()) {
            if (!narrowedCheck.admits("OAUTH", claims, request)) {
                return false;
            }
            OAuthDoors.TenantDoor addressed = OAuthDoors.fromRequestPath(request.getRequestURI());
            if (addressed == null
                    || claims.audience() == null
                    || !claims.audience().equals(doors.resourceUrl(addressed.tenant(), addressed.door()))) {
                log.debug(
                        "OAUTH token rejected: audience '{}' does not name the addressed door {} (grant='{}')",
                        claims.audience(),
                        request.getRequestURI(),
                        grantId);
                return false;
            }
        }
        return grantService.isActive(grantId, claims.tenantId(), claims.username(), claims.projectId());
    }
}
