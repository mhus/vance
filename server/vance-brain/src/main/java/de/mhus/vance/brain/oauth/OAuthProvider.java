package de.mhus.vance.brain.oauth;

import java.net.URI;

/**
 * Bean-type contract for one OAuth provider style — OIDC, generic
 * OAuth 2.0, or a provider-specific quirk subclass (Slack, Atlassian,
 * Google). Implementations are stateless Spring beans discovered by
 * {@link OAuthProviderRegistry} keyed on {@link #typeId()}.
 *
 * <p>Per-instance state (clientId/Secret, scopes, endpoint URLs) lives
 * in {@link OAuthProviderConfig} and is passed in on every call; one
 * bean serves N tenants who share the same flow style.
 *
 * <p>See {@code planning/tool-oauth.md} §2 for the layering rationale.
 */
public interface OAuthProvider {

    /**
     * Stable identifier matched against {@link OAuthProviderConfig#typeId()}.
     * Conventional values: {@code oidc}, {@code generic-oauth2},
     * {@code slack}, {@code atlassian}, {@code google}, {@code github}.
     */
    String typeId();

    /**
     * Build the URL the user-agent gets redirected to in order to
     * authenticate. Implementations are responsible for embedding
     * {@code state}, {@code redirect_uri} and the requested scopes.
     */
    URI buildAuthorizeUri(OAuthProviderConfig cfg, OAuthInitContext ctx);

    /**
     * Exchange the {@code code} returned by the provider's callback for
     * the first {@link OAuthTokenSet}. Throws {@link OAuthFlowException}
     * on any non-2xx response or shape mismatch.
     */
    OAuthTokenSet exchangeCode(OAuthProviderConfig cfg, String code, OAuthInitContext ctx);

    /**
     * Trade a long-lived refresh token for a fresh access token. The
     * returned set may carry a new refresh token (token rotation) — the
     * caller persists whatever comes back. Throws
     * {@link OAuthFlowException} when the provider rejects the refresh
     * (revoked / scope-changed / consent withdrawn).
     */
    OAuthTokenSet refresh(OAuthProviderConfig cfg, String refreshToken);
}
