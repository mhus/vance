package de.mhus.vance.brain.oauthserver;

import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import de.mhus.vance.shared.oauthserver.OAuthServerAuthorizationService;
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
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthTokenService {

    /** RFC 7636 §4.1: 43–128 characters of the unreserved set. */
    private static final Pattern CODE_VERIFIER = Pattern.compile("^[A-Za-z0-9\\-._~]{43,128}$");

    private final OAuthServerSettings settings;
    private final OAuthServerAuthorizationService authorizations;
    private final OAuthServerGrantService grants;
    private final JwtService jwtService;
    private final UserService userService;
    private final OAuthDoors doors;

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

    private static OAuthTokenError invalidGrant(String description) {
        return new OAuthTokenError(HttpStatus.BAD_REQUEST, "invalid_grant", description);
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
        OAuthServerAuthorizationService.Redemption redemption = authorizations.redeemCode(tenant, code);
        if (redemption.replayedGrantId() != null) {
            // Someone presents a code that was already redeemed: either the
            // client retried, or the code leaked. OAuth 2.1 §4.1.3 — revoke
            // what it produced.
            grants.revoke(tenant, redemption.replayedGrantId(), "code-replay");
            throw invalidGrant("authorization code already used");
        }
        OAuthServerCodeDocument redeemed = redemption.code();
        if (redeemed == null) {
            throw invalidGrant("authorization code is invalid or expired");
        }
        if (!clientId.equals(redeemed.getClientId())) {
            throw invalidGrant("authorization code was issued to another client");
        }
        if (!redirectUri.equals(redeemed.getRedirectUri())) {
            throw invalidGrant("redirect_uri does not match the authorization request");
        }
        if (!CODE_VERIFIER.matcher(codeVerifier).matches()
                || !pkceS256(codeVerifier).equals(redeemed.getCodeChallenge())) {
            throw invalidGrant("PKCE verification failed");
        }
        if (!resourceMatches(tenant, resource, redeemed.getResource())) {
            throw new OAuthTokenError(HttpStatus.BAD_REQUEST, "invalid_target", "resource does not match the grant");
        }
        OAuthServerGrantDocument grant = grants.find(tenant, redeemed.getGrantId())
                .filter(g -> g.getRevokedAt() == null)
                .orElseThrow(() -> invalidGrant("the grant is no longer active"));
        requireActiveUser(tenant, grant.getUserId());
        String refreshToken = grants.startRefreshStrand(grant.getGrantId(), settings.refreshTokenTtl());
        if (refreshToken == null) {
            throw invalidGrant("the grant is no longer active");
        }
        log.info(
                "OAuth code redeemed tenant='{}' user='{}' client='{}' scope='{}'",
                tenant,
                grant.getUserId(),
                clientId,
                grant.getScope());
        return tokenResponse(tenant, grant, refreshToken);
    }

    /** {@code grant_type=refresh_token}. */
    public Map<String, Object> refresh(
            String tenant, @Nullable String refreshToken, @Nullable String clientId, @Nullable String resource) {
        requireEnabled();
        if (blank(refreshToken) || blank(clientId)) {
            throw invalidRequest("refresh_token and client_id are required");
        }
        OAuthServerGrantService.RefreshOutcome outcome =
                grants.rotateRefreshToken(tenant, refreshToken, clientId, settings.refreshTokenTtl());
        if (outcome.failure() != null || outcome.grant() == null || outcome.refreshToken() == null) {
            throw invalidGrant(
                    switch (outcome.failure() == null
                            ? OAuthServerGrantService.RefreshFailure.INVALID
                            : outcome.failure()) {
                        case INVALID -> "refresh token is invalid";
                        case EXPIRED -> "refresh token expired or the grant was revoked";
                        case REPLAYED -> "refresh token was already used — the grant has been revoked";
                    });
        }
        OAuthServerGrantDocument grant = outcome.grant();
        if (!resourceMatches(tenant, resource, grant.getResource())) {
            throw new OAuthTokenError(HttpStatus.BAD_REQUEST, "invalid_target", "resource does not match the grant");
        }
        requireActiveUser(tenant, grant.getUserId());
        return tokenResponse(tenant, grant, outcome.refreshToken());
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
        grants.revokeByRefreshToken(tenant, token);
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
            throw invalidGrant("the account cannot log in");
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
