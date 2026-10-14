package de.mhus.vance.brain.oauthserver;

import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.oauthserver.OAuthServerAuthorizationService;
import de.mhus.vance.shared.oauthserver.OAuthServerClientService;
import de.mhus.vance.shared.oauthserver.OAuthServerCodeDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The token endpoint's logic: redeem a code (PKCE), rotate a refresh token,
 * revoke. Returns OAuth wire maps; errors are {@link OAuthTokenError}.
 *
 * <p><b>Check first, spend second.</b> A code or refresh token is only
 * consumed after everything about the request has been verified — a request
 * that fails a binding check leaves the credential as it was, so a stranger
 * holding someone's code cannot burn it by presenting it wrongly, and only a
 * presenter that passes the client binding can trigger replay revocation.
 *
 * <p>Every {@code invalid_grant} carries the same generic description; which
 * check failed is logged, not answered — the response must not help a caller
 * probe codes and tokens.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthTokenService {

    /** RFC 7636 §4.1: 43–128 characters of the unreserved set. */
    private static final Pattern CODE_VERIFIER = Pattern.compile("^[A-Za-z0-9\\-._~]{43,128}$");

    /** The one description every {@code invalid_grant} answer carries. */
    static final String INVALID_GRANT_DESCRIPTION = "the authorization grant is invalid, expired or revoked";

    private final OAuthServerSettings settings;
    private final OAuthServerAuthorizationService authorizations;
    private final OAuthServerGrantService grants;
    private final JwtService jwtService;
    private final UserService userService;
    private final OAuthDoors doors;
    private final OAuthServerClientService clientService;

    /** An OAuth error response of the token endpoint (RFC 6749 §5.2). */
    public static class OAuthTokenError extends RuntimeException {

        private final HttpStatus status;
        private final String error;

        public OAuthTokenError(HttpStatus status, String error, String description) {
            super(description);
            this.status = status;
            this.error = error;
        }

        public HttpStatus status() {
            return status;
        }

        public String error() {
            return error;
        }
    }

    private static OAuthTokenError invalidGrant() {
        return new OAuthTokenError(HttpStatus.BAD_REQUEST, "invalid_grant", INVALID_GRANT_DESCRIPTION);
    }

    private static OAuthTokenError invalidRequest(String description) {
        return new OAuthTokenError(HttpStatus.BAD_REQUEST, "invalid_request", description);
    }

    /** {@code grant_type=authorization_code}. */
    public Map<String, Object> redeemCode(
            String tenant,
            @Nullable String code,
            @Nullable String redirectUri,
            @Nullable String clientId,
            @Nullable String codeVerifier,
            @Nullable String resource) {
        requireEnabled();
        if (blank(code) || blank(redirectUri) || blank(clientId) || blank(codeVerifier)) {
            throw invalidRequest("code, redirect_uri, client_id and code_verifier are required");
        }
        OAuthServerCodeDocument found = authorizations.findCode(tenant, code).orElse(null);
        if (found == null) {
            log.trace("OAuth code refused tenant='{}' client='{}': unknown code", tenant, clientId);
            throw invalidGrant();
        }
        // The binding: who may present this code at all. A failure here changes
        // nothing — neither the code nor the grant.
        if (!clientId.equals(found.getClientId())) {
            log.warn(
                    "OAuth code refused tenant='{}': presented by client '{}', issued to '{}'",
                    tenant,
                    clientId,
                    found.getClientId());
            throw invalidGrant();
        }
        if (!redirectUri.equals(found.getRedirectUri())) {
            log.trace("OAuth code refused tenant='{}' client='{}': redirect_uri mismatch", tenant, clientId);
            throw invalidGrant();
        }
        if (!CODE_VERIFIER.matcher(codeVerifier).matches()
                || !pkceS256(codeVerifier).equals(found.getCodeChallenge())) {
            log.trace("OAuth code refused tenant='{}' client='{}': PKCE verification failed", tenant, clientId);
            throw invalidGrant();
        }
        if (found.getUsedAt() != null) {
            // The rightful client presents a code a second time: either it
            // retried, or the code leaked together with the verifier. OAuth 2.1
            // §4.1.3 — revoke what it produced.
            grants.revokeAfterReplay(tenant, found.getGrantId(), "code");
            throw invalidGrant();
        }
        if (authorizations.isExpired(found)) {
            log.trace("OAuth code refused tenant='{}' client='{}': expired", tenant, clientId);
            throw invalidGrant();
        }
        if (!resourceMatches(tenant, resource, found.getResource())) {
            throw new OAuthTokenError(HttpStatus.BAD_REQUEST, "invalid_target", "resource does not match the grant");
        }
        OAuthServerGrantDocument grant = grants.find(tenant, found.getGrantId())
                .filter(g -> g.getRevokedAt() == null)
                .orElse(null);
        if (grant == null) {
            log.trace("OAuth code refused tenant='{}' client='{}': grant no longer active", tenant, clientId);
            throw invalidGrant();
        }
        requireActiveUser(tenant, grant.getUserId());
        if (!authorizations.consumeCode(tenant, code)) {
            // Spent (or expired) between the read and the stamp. Spent means a
            // concurrent redemption that also passed the binding — a replay.
            boolean spent = authorizations
                    .findCode(tenant, code)
                    .map(c -> c.getUsedAt() != null)
                    .orElse(false);
            if (spent) {
                grants.revokeAfterReplay(tenant, found.getGrantId(), "code");
            }
            throw invalidGrant();
        }
        String refreshToken = grants.startRefreshStrand(grant.getGrantId(), settings.refreshTokenTtl());
        if (refreshToken == null) {
            log.trace("OAuth code refused tenant='{}' client='{}': grant revoked meanwhile", tenant, clientId);
            throw invalidGrant();
        }
        clientService.touch(clientId);
        log.info(
                "OAuth code redeemed tenant='{}' user='{}' client='{}' scope='{}'",
                tenant,
                grant.getUserId(),
                clientId,
                grant.getScope());
        return tokenResponse(tenant, grant, refreshToken);
    }

    /**
     * {@code grant_type=refresh_token}. The token is inspected, the request
     * checked against the grant (resource, account), and only then rotated —
     * a refused request leaves the token valid.
     */
    public Map<String, Object> refresh(
            String tenant, @Nullable String refreshToken, @Nullable String clientId, @Nullable String resource) {
        requireEnabled();
        if (blank(refreshToken) || blank(clientId)) {
            throw invalidRequest("refresh_token and client_id are required");
        }
        OAuthServerGrantService.RefreshOutcome checked = grants.inspectRefreshToken(tenant, refreshToken, clientId);
        OAuthServerGrantDocument grant = checked.grant();
        if (checked.failure() != null || grant == null) {
            log.trace("OAuth refresh refused tenant='{}' client='{}': {}", tenant, clientId, checked.failure());
            throw invalidGrant();
        }
        if (!resourceMatches(tenant, resource, grant.getResource())) {
            throw new OAuthTokenError(HttpStatus.BAD_REQUEST, "invalid_target", "resource does not match the grant");
        }
        requireActiveUser(tenant, grant.getUserId());
        OAuthServerGrantService.RefreshOutcome rotated =
                grants.rotateRefreshToken(tenant, refreshToken, clientId, settings.refreshTokenTtl());
        if (rotated.failure() != null || rotated.grant() == null || rotated.refreshToken() == null) {
            log.trace(
                    "OAuth refresh refused at rotation tenant='{}' client='{}': {}",
                    tenant,
                    clientId,
                    rotated.failure());
            throw invalidGrant();
        }
        return tokenResponse(tenant, rotated.grant(), rotated.refreshToken());
    }

    /**
     * RFC 7009 revocation. Answers success whether or not anything was
     * revoked — the response must not tell a caller whether a token existed.
     */
    public void revoke(String tenant, @Nullable String token, @Nullable String clientId) {
        if (blank(token) || !settings.enabled()) {
            return;
        }
        if (token.chars().filter(c -> c == '.').count() == 2) {
            VanceJwtClaims claims = jwtService.validateToken(token).orElse(null);
            if (claims != null
                    && claims.tokenType() == TokenType.OAUTH
                    && tenant.equals(claims.tenantId())
                    && claims.tokenId() != null
                    && (blank(clientId) || clientId.equals(claims.clientId()))) {
                grants.revoke(tenant, claims.tokenId(), "client");
            }
            return;
        }
        grants.revokeByRefreshToken(tenant, token, clientId);
    }

    private Map<String, Object> tokenResponse(String tenant, OAuthServerGrantDocument grant, String refreshToken) {
        Instant expiresAt = Instant.now().plus(settings.accessTokenTtl());
        String accessToken = jwtService.createOAuthAccessToken(
                tenant,
                grant.getUserId(),
                grant.getGrantId(),
                grant.getClientId(),
                grant.getScopeProfiles(),
                grant.getProjectId(),
                grant.getResource(),
                expiresAt);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", accessToken);
        out.put("token_type", "Bearer");
        out.put("expires_in", settings.accessTokenTtl().toSeconds());
        out.put("refresh_token", refreshToken);
        out.put("scope", grant.getScope());
        return out;
    }

    /**
     * A grant acts as its account, so the account must still be allowed to
     * log in — a disabled or locked-out user's tokens must stop at the next
     * refresh, not when the grant ends a year later.
     */
    private void requireActiveUser(String tenant, String userId) {
        UserDocument user = userService.findByTenantAndName(tenant, userId).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE || !user.isLoginEnabled()) {
            log.trace("OAuth token refused tenant='{}': account '{}' cannot log in", tenant, userId);
            throw invalidGrant();
        }
    }

    /** An absent {@code resource} is fine; a present one must name the grant's door. */
    private boolean resourceMatches(String tenant, @Nullable String presented, @Nullable String granted) {
        if (blank(presented)) {
            return true;
        }
        if (granted == null) {
            return false;
        }
        OAuthDoor a = doors.fromResource(tenant, presented);
        OAuthDoor b = doors.fromResource(tenant, granted);
        return a != null && a.equals(b);
    }

    private void requireEnabled() {
        if (!settings.enabled()) {
            throw new OAuthTokenError(
                    HttpStatus.SERVICE_UNAVAILABLE, "temporarily_unavailable", "the OAuth server is not configured");
        }
    }

    /** {@code BASE64URL(SHA256(ASCII(verifier)))} without padding (RFC 7636 §4.2). */
    static String pkceS256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
