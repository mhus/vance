package de.mhus.vance.brain.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the secret-claim classification that decides which OAuth extra
 * claims are stored encrypted vs. as plaintext metadata (code-review F4),
 * plus the secret-masking contract of {@link OAuthTokenSet#toString()}.
 */
class OAuthTokenSetTest {

    @Test
    void isSecretClaimKey_flagsBearerTokenFamily() {
        assertThat(OAuthTokenSet.isSecretClaimKey("access_token")).isTrue();
        assertThat(OAuthTokenSet.isSecretClaimKey("bot_access_token")).isTrue(); // Slack
        assertThat(OAuthTokenSet.isSecretClaimKey("refresh_token")).isTrue();
        assertThat(OAuthTokenSet.isSecretClaimKey("id_token")).isTrue();          // OIDC
        assertThat(OAuthTokenSet.isSecretClaimKey("client_secret")).isTrue();
    }

    @Test
    void isSecretClaimKey_leavesNonSecretMetadataAsString() {
        assertThat(OAuthTokenSet.isSecretClaimKey("token_type")).isFalse();
        assertThat(OAuthTokenSet.isSecretClaimKey("scope")).isFalse();
        assertThat(OAuthTokenSet.isSecretClaimKey("expires_in")).isFalse();
        assertThat(OAuthTokenSet.isSecretClaimKey("cloud_id")).isFalse();
        assertThat(OAuthTokenSet.isSecretClaimKey("team_id")).isFalse();
    }

    @Test
    void toString_masksSecretExtraClaimsButKeepsMetadata() {
        OAuthTokenSet set = new OAuthTokenSet(
                "AT", "RT", Instant.EPOCH,
                Map.of("bot_access_token", "xoxb-super-secret", "team_id", "T123"));

        String s = set.toString();

        assertThat(s).doesNotContain("xoxb-super-secret");
        assertThat(s).contains("bot_access_token=***");
        assertThat(s).contains("team_id=T123");
    }
}
