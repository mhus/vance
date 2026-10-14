package de.mhus.vance.foot.auth;

import org.jspecify.annotations.Nullable;

/**
 * The OAuth calls of the browser login — discovery, code exchange, refresh,
 * revoke. Behind an interface so {@link FootAuthService} and
 * {@link OAuthBrowserLogin} can be unit-tested without HTTP.
 */
public interface OAuthClient {

    /** The endpoints of a tenant's authorization server. */
    record Endpoints(
            String issuer,
            String authorizationEndpoint,
            String tokenEndpoint,
            @Nullable String revocationEndpoint) {}

    /** A token endpoint answer. {@code expiresIn} in seconds. */
    record Tokens(
            String accessToken, long expiresIn, @Nullable String refreshToken) {}

    /** Thrown when the token endpoint refuses ({@code invalid_grant} & co) — the login is gone. */
    class OAuthRefusedException extends Exception {
        public OAuthRefusedException(String message) {
            super(message);
        }
    }

    /**
     * Reads the authorization server metadata of {@code tenant} at
     * {@code httpBase} — the issuer-appended discovery URL, which works
     * through the ordinary {@code /brain} route.
     */
    Endpoints discover(String httpBase, String tenant) throws Exception;

    Tokens exchangeCode(Endpoints endpoints, String clientId, String code, String redirectUri, String codeVerifier)
            throws Exception;

    Tokens refresh(String tokenEndpoint, String clientId, String refreshToken) throws Exception;

    /**
     * Best effort — never throws. Returns {@code true} when the server
     * confirmed the revocation (RFC 7009: HTTP 200, also for a token it
     * already considered dead), {@code false} when it could not be reached or
     * answered otherwise — the grant may then still be live on the server.
     */
    boolean revoke(String revocationEndpoint, String clientId, String token);
}
