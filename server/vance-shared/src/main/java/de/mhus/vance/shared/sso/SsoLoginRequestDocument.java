package de.mhus.vance.shared.sso;

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
 * One SSO sign-in in flight: written when the browser is sent to the identity
 * provider, taken (and deleted) exactly once by the callback. TTL-evicted.
 *
 * <p>Only the hash of {@code state} is stored — the value itself travels in
 * the redirect and in a browser cookie, and the callback needs both.
 */
@Document(collection = "sso_login_requests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SsoLoginRequestDocument {

    @Id
    private @Nullable String id;

    /** SHA-256 of the {@code state} parameter. */
    @Indexed(unique = true)
    private String stateHash = "";

    /** OIDC {@code nonce} the ID token must echo. */
    private String nonce = "";

    /** PKCE verifier for the code exchange. */
    private String codeVerifier = "";

    /** Provider key from the SSO config. */
    private String provider = "";

    /** {@code domain} (Workspace via {@code hd}) or {@code consumer} (private Gmail). */
    private String mode = "";

    /** Expected domain for {@code mode=domain}; {@code null} for consumer sign-ins. */
    private @Nullable String domain;

    /**
     * Tenant the sign-in must end in — from {@code ?tenant=} or the
     * {@code next} path. Mandatory for consumer sign-ins.
     */
    private @Nullable String expectedTenant;

    /** Same-origin path to go to afterwards. */
    private @Nullable String next;

    private @Nullable Instant createdAt;

    @Indexed(expireAfter = "0s")
    private Instant expiresAt = Instant.now();
}
