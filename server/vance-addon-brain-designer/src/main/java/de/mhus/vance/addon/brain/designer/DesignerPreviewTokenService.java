package de.mhus.vance.addon.brain.designer;

import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Mints and validates the {@link TokenType#DESIGN_PREVIEW} tokens that
 * carry the designer app's sandboxed content route.
 *
 * <p><b>Why a token in the path.</b> The preview iframe is sandboxed to an
 * opaque origin ({@code sandbox="allow-scripts"}): its document and its
 * sub-resource requests carry no cookies and no Authorization header, and
 * a query-parameter token would be dropped by the first relative
 * sub-resource URL the design's HTML resolves. A <em>path segment</em>
 * survives relative resolution — {@code css/style.css} asked from
 * {@code .../content/<doc>/<token>/<design>/} lands inside the same token
 * prefix — which is why the content route is shaped the way it is.
 *
 * <p><b>What a leak is worth.</b> The token type is rejected by every
 * access filter as a bearer; only the designer content controller accepts
 * it, and it re-runs the per-request READ check against the minting user's
 * identity. A stolen token re-reads, until {@code expiresAt}, exactly the
 * files its minter could read in that one app folder.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DesignerPreviewTokenService {

    /** Default preview-token TTL in seconds (30 minutes). */
    public static final long DEFAULT_TTL_SECONDS = 1800;

    private final JwtService jwtService;

    /**
     * Preview-token TTL in seconds. Kebab-case property name — see the
     * property-naming rule in the cross-cutting code rules.
     */
    @Value("${vance.designer.preview-token-ttl-seconds:" + DEFAULT_TTL_SECONDS + "}")
    private long previewTokenTtlSeconds;

    /**
     * Mints a fresh preview token for one designer-app folder. The caller
     * has already enforced READ on the app manifest — minting is not an
     * authorisation decision, only the credential hand-off.
     */
    public DesignerPreviewSession mint(String tenantId, String projectId, String appFolder, String username) {
        String folder = DesignerPaths.normaliseFolder(appFolder);
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds());
        String token = jwtService.createDesignPreviewToken(tenantId, username, projectId, folder, expiresAt);
        log.debug(
                "designer preview token minted tenant='{}' project='{}' folder='{}' user='{}'",
                tenantId,
                projectId,
                folder,
                username);
        return new DesignerPreviewSession(token, expiresAt);
    }
    /**
     * Validates a presented token against the expected tenant and returns
     * its verified claims, or empty when the token is wrong in any way: bad
     * signature, expired, wrong type, tenant mismatch, or missing scope
     * claims. Cross-checks against the addressed app document happen in
     * the controller, which knows what the URL says.
     */
    public Optional<VanceJwtClaims> validate(String token, String expectedTenantId) {
        Optional<VanceJwtClaims> verified = jwtService.validateToken(token);
        if (verified.isEmpty()) {
            return Optional.empty();
        }
        VanceJwtClaims claims = verified.get();
        if (claims.tokenType() != TokenType.DESIGN_PREVIEW) {
            return Optional.empty();
        }
        if (!claims.tenantId().equals(expectedTenantId)) {
            return Optional.empty();
        }
        if (claims.projectId() == null
                || claims.projectId().isBlank()
                || claims.appFolder() == null
                || claims.appFolder().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(claims);
    }

    private long ttlSeconds() {
        return previewTokenTtlSeconds > 0 ? previewTokenTtlSeconds : DEFAULT_TTL_SECONDS;
    }
}
