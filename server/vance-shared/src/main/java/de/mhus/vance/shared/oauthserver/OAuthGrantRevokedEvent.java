package de.mhus.vance.shared.oauthserver;

/**
 * Published by {@link OAuthServerGrantService} after a grant went from live
 * to revoked — whoever holds state derived from the grant (an open WebSocket
 * authenticated with one of its tokens, a cache) drops it on this signal
 * instead of waiting for the liveness cache window.
 *
 * <p>Fired once per effective revocation, never for a grant that was already
 * revoked. Local to the pod that revoked; other pods still learn it through
 * the liveness check.
 *
 * @param tenantId tenant of the grant
 * @param grantId  the revoked grant — {@code jti} of all its access tokens
 * @param userId   the account the grant acted as
 * @param reason   revoke reason as stored on the row ({@code user}, {@code client},
 *                 {@code code-replay}, {@code refresh-replay}, {@code password-changed},
 *                 {@code project-renamed}, {@code project-deleted})
 */
public record OAuthGrantRevokedEvent(String tenantId, String grantId, String userId, String reason) {}
