package de.mhus.vance.shared.oauthserver;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An issued authorization code — stored as its hash, single use, short-lived.
 *
 * <p>A redeemed code is not deleted but stamped {@link #usedAt}: a second
 * redemption is then recognisable as a replay (someone else holds the code)
 * and revokes the grant, as OAuth 2.1 §4.1.3 asks. The TTL index removes the
 * row a while after expiry either way.
 */
@Document(collection = "oauth_server_codes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthServerCodeDocument {

    @Id
    private @Nullable String id;

    /** SHA-256 of the code. */
    @Indexed(unique = true)
    private String codeHash = "";

    private String tenantId = "";

    private String userId = "";

    private String grantId = "";

    private String clientId = "";

    private String redirectUri = "";

    private String codeChallenge = "";

    private @Nullable String resource;

    private @Nullable Instant usedAt;

    /** Validity end — checked by the application; the TTL index only cleans up. */
    private Instant expiresAt = Instant.now();

    /** When Mongo may drop the row — later than {@link #expiresAt} so replays stay detectable. */
    @Indexed(expireAfterSeconds = 0)
    private Instant purgeAt = Instant.now();
}
