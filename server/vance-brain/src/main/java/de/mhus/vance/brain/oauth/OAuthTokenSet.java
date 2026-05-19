package de.mhus.vance.brain.oauth;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of {@link OAuthProvider#exchangeCode} or
 * {@link OAuthProvider#refresh}. Stored across two
 * {@code SettingType.PASSWORD} user-settings (access + refresh) plus
 * {@code STRING} settings for the public-side metadata; see
 * {@code planning/tool-oauth.md §3.2}.
 *
 * @param accessToken   the bearer token to send with tool calls
 * @param refreshToken  {@code null} when the provider does not issue
 *                      one (or when the access token never expires)
 * @param expiresAt     absolute expiry timestamp; {@code null} when the
 *                      provider didn't advertise a lifetime
 * @param extraClaims   provider-specific extras worth persisting —
 *                      {@code team_id} for Slack, {@code cloud_id} for
 *                      Atlassian, the original {@code scope} string,
 *                      {@code token_type}, …
 */
public record OAuthTokenSet(
        String accessToken,
        @Nullable String refreshToken,
        @Nullable Instant expiresAt,
        Map<String, String> extraClaims) {

    @Override
    public String toString() {
        // Excludes the tokens themselves so logs never carry them.
        return "OAuthTokenSet{"
                + "accessToken=***"
                + ", refreshToken=" + (refreshToken == null ? "null" : "***")
                + ", expiresAt=" + expiresAt
                + ", extraClaims=" + extraClaims
                + '}';
    }
}
