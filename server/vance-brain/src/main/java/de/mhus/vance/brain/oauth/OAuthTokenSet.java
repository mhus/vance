package de.mhus.vance.brain.oauth;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
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
        // Excludes the tokens themselves so logs never carry them — including
        // secret-bearing extra claims (bot_access_token, id_token, …) which
        // are masked to their key names only (code-review F4).
        String claims = extraClaims.entrySet().stream()
                .map(e -> e.getKey() + "="
                        + (isSecretClaimKey(e.getKey()) ? "***" : e.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
        return "OAuthTokenSet{"
                + "accessToken=***"
                + ", refreshToken=" + (refreshToken == null ? "null" : "***")
                + ", expiresAt=" + expiresAt
                + ", extraClaims=" + claims
                + '}';
    }

    /**
     * Whether an extra-claim key carries a secret and must be stored
     * encrypted (PASSWORD), not as a plaintext STRING setting. Matches the
     * bearer-token family ({@code access_token}, {@code refresh_token},
     * {@code id_token}, any {@code *_token}, {@code secret}/{@code password})
     * while excluding non-secret metadata such as {@code token_type},
     * {@code scope} and {@code expires_in} (code-review F4).
     */
    public static boolean isSecretClaimKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.equals("token_type") || k.equals("scope") || k.equals("expires_in")) {
            return false;
        }
        return k.endsWith("access_token")
                || k.endsWith("refresh_token")
                || k.endsWith("id_token")
                || k.endsWith("_token")
                || k.equals("token")
                || k.contains("secret")
                || k.contains("password");
    }
}
