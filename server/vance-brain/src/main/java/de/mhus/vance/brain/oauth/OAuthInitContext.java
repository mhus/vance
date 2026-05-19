package de.mhus.vance.brain.oauth;

import org.jspecify.annotations.Nullable;

/**
 * Per-request inputs that the provider beans need to build the
 * authorize-URL and to exchange the authorization code. Constructed by
 * the {@code OAuthController} from path variables, the authenticated
 * JWT subject, and the deployment's public base URL.
 *
 * @param tenantId     the path tenant of the caller
 * @param userId       authenticated user — owner of the resulting tokens
 * @param state        single-use CSRF/state token persisted in
 *                     {@code OAuthStateDocument} until the callback
 *                     consumes it
 * @param redirectUri  absolute callback URL ({@code https://<brain>/brain/{tenant}/oauth/{providerId}/callback})
 * @param returnTo     Web-UI path the browser should be redirected to
 *                     after the callback completes; {@code null} for the
 *                     UI's default
 * @param codeVerifier PKCE code-verifier (RFC 7636) — minted on init,
 *                     persisted on the state document, replayed on
 *                     callback. {@code null} when the provider is not
 *                     configured for PKCE
 */
public record OAuthInitContext(
        String tenantId,
        String userId,
        String state,
        String redirectUri,
        @Nullable String returnTo,
        @Nullable String codeVerifier) {

    public OAuthInitContext(
            String tenantId,
            String userId,
            String state,
            String redirectUri,
            @Nullable String returnTo) {
        this(tenantId, userId, state, redirectUri, returnTo, null);
    }
}
