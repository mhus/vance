package de.mhus.vance.brain.oauth;

import org.jspecify.annotations.Nullable;

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
 *
 * <p>{@code userId} is {@code null} when a shared login — the project- or
 * tenant-wide account of an MCP server — was replaced or removed; every
 * connection of the tenant that may have used it has to go.
 */
public record OAuthDisconnectedEvent(
        String tenantId, @Nullable String userId, String providerId) {

    /** Whether a shared (project / tenant) login changed — {@code userId} is then {@code null}. */
    public boolean shared() {
        return userId == null;
    }
}
