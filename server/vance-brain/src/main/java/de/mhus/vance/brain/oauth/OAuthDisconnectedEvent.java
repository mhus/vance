package de.mhus.vance.brain.oauth;

/**
 * Published right after the OAuth disconnect endpoint has wiped the
 * user's tokens. Subscribers ({@code McpToolPackFactory},
 * {@code RestApiToolPackFactory}, …) use it to drop any cached state
 * that authenticated against those tokens — open MCP sessions, pooled
 * REST clients with bound bearer headers, in-flight token-refresh
 * coordinators, …
 *
 * <p>The event fires <i>after</i> the tokens are gone from
 * {@link de.mhus.vance.shared.settings.SettingService}, so listeners
 * that look up the user's settings to verify the disconnect see the
 * post-state. Listeners must <b>not</b> block — failure to clean up a
 * cached connection should be logged, not thrown, because the user's
 * disconnect is already persisted.
 */
public record OAuthDisconnectedEvent(String tenantId, String userId, String providerId) {
}
