package de.mhus.vance.brain.access;

import de.mhus.vance.shared.integration.IntegrationTokenService;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Validates {@code INTEGRATION} JWTs for the brain access filter — the second
 * half of the credential, next to the signature.
 *
 * <p>Four checks, each closing a different hole (the structural ones —
 * profile, surface, pin — live in {@link NarrowedCredentialCheck}, shared
 * with narrowed OAuth tokens):
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

    private final IntegrationTokenService tokenService;
    private final NarrowedCredentialCheck narrowedCheck;

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
        if (!narrowedCheck.admits("INTEGRATION", claims, request)) {
            return false;
        }
        // Liveness last: it is the only check that touches the database, so the
        // cheap structural rejections happen first.
        return tokenService.isActive(tokenId, claims.tenantId(), claims.username(), claims.projectId());
    }
}
