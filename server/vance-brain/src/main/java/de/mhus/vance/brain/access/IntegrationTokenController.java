package de.mhus.vance.brain.access;

import de.mhus.vance.api.access.IntegrationScopeProfileDto;
import de.mhus.vance.api.access.IntegrationTokenCreateRequest;
import de.mhus.vance.api.access.IntegrationTokenDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.integration.IntegrationTokenDocument;
import de.mhus.vance.shared.integration.IntegrationTokenService;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mint, list and revoke integration tokens — {@code /brain/{tenant}/integration-tokens}.
 *
 * <p>A caller mints only for themselves, and only with an
 * {@link TokenType#ACCESS} token. Both halves matter:
 *
 * <ul>
 *   <li><b>Only for themselves</b>, because the token acts as an account and
 *       minting one for somebody else is impersonation with extra steps.</li>
 *   <li><b>Only from an ACCESS token</b>, because otherwise a confined
 *       credential could mint a differently-confined one and walk out of its
 *       own restriction one profile at a time. This is the same reason
 *       {@code AccessController.refreshToken} refuses anything but ACCESS —
 *       an attenuated credential must never be a source of credentials.</li>
 * </ul>
 *
 * <p>The mint additionally requires {@code WRITE} on the project being pinned.
 * The permission intersection would make an over-broad token harmless anyway,
 * but a token that authenticates and then fails every call is a bad thing to
 * hand somebody: better to refuse at the moment the mistake is made.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class IntegrationTokenController {

    /**
     * Cap on the requested lifetime. Generous, because expiry is the safety
     * net and revocation is the control — but not unbounded: a credential
     * nobody remembers minting should stop on its own eventually.
     */
    private static final int MAX_EXPIRES_IN_DAYS = 365;

    private static final int DEFAULT_EXPIRES_IN_DAYS = 90;

    private final JwtService jwtService;
    private final IntegrationTokenService tokenService;
    private final IntegrationScopeRegistry profiles;
    private final RequestAuthority authority;
    private final AuditService auditService;

    /** The profiles this brain can mint for — the mint form's source of truth. */
    @GetMapping("/brain/{tenant}/integration-tokens/profiles")
    public List<IntegrationScopeProfileDto> listProfiles(@PathVariable String tenant) {
        List<IntegrationScopeProfileDto> out = new ArrayList<>();
        for (IntegrationScopeProfile profile : profiles.all()) {
            List<String> surfaces = new ArrayList<>();
            for (IntegrationSurface surface : profile.surfaces()) {
                surfaces.add(surface.method() + " " + surface.pathPattern());
            }
            out.add(IntegrationScopeProfileDto.builder()
                    .id(profile.id())
                    .label(profile.label())
                    .requiresProject(profile.requiresProject())
                    .surfaces(surfaces)
                    .build());
        }
        return out;
    }

    @GetMapping("/brain/{tenant}/integration-tokens")
    public List<IntegrationTokenDto> list(@PathVariable String tenant,
                                          HttpServletRequest request) {
        String username = requireUser(request);
        List<IntegrationTokenDto> out = new ArrayList<>();
        for (IntegrationTokenDocument doc : tokenService.list(tenant, username)) {
            out.add(toDto(doc, null));
        }
        return out;
    }

    @PostMapping("/brain/{tenant}/integration-tokens")
    public ResponseEntity<IntegrationTokenDto> create(
            @PathVariable String tenant,
            @Valid @RequestBody IntegrationTokenCreateRequest req,
            HttpServletRequest request) {

        String username = requireUser(request);
        requireAccessToken(request);

        // Resolve every requested profile before doing anything: a mint that
        // half-succeeded would hand out a credential narrower than the person
        // asked for, and they would find out at the first call.
        List<IntegrationScopeProfile> granted = new ArrayList<>();
        for (String id : req.getScopeProfiles()) {
            granted.add(profiles.find(id).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Unknown scope profile '" + id + "'")));
        }

        String projectId = blankToNull(req.getProjectId());
        if (granted.stream().anyMatch(IntegrationScopeProfile::requiresProject)
                && projectId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "One of the requested scope profiles requires a projectId");
        }
        if (projectId != null) {
            authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        }

        int days = req.getExpiresInDays() <= 0 ? DEFAULT_EXPIRES_IN_DAYS : req.getExpiresInDays();
        if (days > MAX_EXPIRES_IN_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "expiresInDays must be at most " + MAX_EXPIRES_IN_DAYS);
        }
        Instant expiresAt = Instant.now().plus(Duration.ofDays(days));

        // Row first, token second — see IntegrationTokenService.create.
        List<String> profileIds = granted.stream().map(IntegrationScopeProfile::id).toList();
        IntegrationTokenDocument doc = tokenService.create(
                tenant, username, profileIds, projectId,
                req.getLabel().trim(), username, expiresAt);
        String token = jwtService.createIntegrationToken(
                tenant, username, doc.getTokenId(), profileIds, projectId, expiresAt);

        auditService.authIntegrationTokenIssued(
                tenant, username, profileIds, projectId, doc.getTokenId());
        log.info("Minted integration token tenant='{}' user='{}' profiles={} project='{}' "
                        + "label='{}' jti='{}'",
                tenant, username, profileIds, projectId == null ? "" : projectId,
                doc.getLabel(), doc.getTokenId());
        return ResponseEntity.ok(toDto(doc, token));
    }

    @DeleteMapping("/brain/{tenant}/integration-tokens/{tokenId}")
    public ResponseEntity<Void> revoke(@PathVariable String tenant,
                                       @PathVariable String tokenId,
                                       HttpServletRequest request) {
        String username = requireUser(request);
        IntegrationTokenDocument doc = tokenService.find(tenant, tokenId).orElse(null);
        // Somebody else's token is "not found", not "forbidden" — the same
        // stance the client roster takes: a stranger must not learn that an id
        // exists by being told they may not touch it.
        if (doc == null || !username.equals(doc.getUserId())) {
            return ResponseEntity.notFound().build();
        }
        tokenService.revoke(tenant, tokenId);
        auditService.authIntegrationTokenRevoked(tenant, username, tokenId);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ───────────────────────────────────────────────────

    private IntegrationTokenDto toDto(IntegrationTokenDocument doc, @Nullable String token) {
        return IntegrationTokenDto.builder()
                .tokenId(doc.getTokenId())
                .token(token)
                .scopeProfiles(doc.getScopeProfiles())
                .scopeProfileLabels(doc.getScopeProfiles().stream()
                        // A profile the brain no longer has still shows up, by
                        // its id: the owner needs to see that the token carries
                        // something dead, not a gap in the list.
                        .map(id -> profiles.find(id)
                                .map(IntegrationScopeProfile::label).orElse(id))
                        .toList())
                .projectId(doc.getProjectId())
                .label(doc.getLabel())
                .createdAtTimestamp(millis(doc.getCreatedAt()))
                .expiresAtTimestamp(millis(doc.getExpiresAt()))
                .lastUsedAtTimestamp(millis(doc.getLastUsedAt()))
                .revokedAtTimestamp(millis(doc.getRevokedAt()))
                .build();
    }

    private static String requireUser(HttpServletRequest request) {
        String username = AccessFilterBase.usernameOrNull(request);
        if (username == null) {
            // Defensive — the filter would have rejected this already.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return username;
    }

    /**
     * Refuse anything but a login-issued ACCESS token. An attenuated
     * credential must not be able to mint credentials.
     */
    private static void requireAccessToken(HttpServletRequest request) {
        Object claims = request.getAttribute(AccessFilterBase.ATTR_CLAIMS);
        if (!(claims instanceof VanceJwtClaims c) || c.tokenType() != TokenType.ACCESS) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Integration tokens can only be minted with a login token");
        }
    }

    private static @Nullable Long millis(@Nullable Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
