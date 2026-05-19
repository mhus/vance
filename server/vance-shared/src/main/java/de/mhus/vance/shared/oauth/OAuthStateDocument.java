package de.mhus.vance.shared.oauth;

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
 * One in-flight OAuth authorization code-grant flow. Created by
 * {@code GET /brain/{tenant}/oauth/{providerId}/init} before the
 * browser is redirected to the provider, consumed by
 * {@code GET /brain/{tenant}/oauth/{providerId}/callback} on return.
 *
 * <p>{@code state} is the random CSRF token round-tripped through the
 * provider; the matching document binds it to the originating
 * {@code (tenantId, userId, providerId)} so the callback can verify
 * the browser session matches the initiator. Single-use — the consume
 * step deletes the document.
 *
 * <p>A TTL index on {@link #expiresAt} (with {@code expireAfterSeconds=0})
 * lets Mongo evict abandoned flows on its own, so the collection stays
 * small even if init endpoints get probed at scale.
 */
@Document(collection = "oauth_states")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthStateDocument {

    @Id
    private @Nullable String id;

    /** Random CSRF token (128-bit, Base64URL-encoded). Unique. */
    @Indexed(unique = true)
    private String state = "";

    /** Tenant that initiated the flow. */
    private String tenantId = "";

    /** User who initiated the flow. */
    private String userId = "";

    /** Provider id from {@code _tenant/oauth/<providerId>.yaml}. */
    private String providerId = "";

    /** Optional Web-UI path the callback should redirect to. */
    private @Nullable String returnTo;

    /**
     * PKCE code-verifier (RFC 7636) when the provider is configured
     * with {@code usePkce: true}. Plaintext is acceptable — the document
     * has a TTL of minutes, is never returned over the network, and the
     * verifier is single-use (consumed and deleted with the state).
     */
    private @Nullable String codeVerifier;

    /** When the document was created — purely informational. */
    private Instant createdAt = Instant.now();

    /**
     * Absolute expiry. Mongo's TTL monitor drops the doc some time
     * after this passes — but the application is the authoritative
     * checker: {@code OAuthStateService.consume} rejects states whose
     * {@code expiresAt} has passed even if the TTL hasn't fired yet.
     */
    @Indexed(expireAfterSeconds = 0)
    private Instant expiresAt = Instant.now();
}
