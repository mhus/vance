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
 * A completed SSO sign-in waiting to be picked up by a native app's WebView
 * ({@code specification/public/sso-login.md} §8). The sign-in ran in the
 * system browser; its cookies would land there, not in the app. Instead the
 * callback leaves this record and sends the browser back to the app with a
 * one-time code; the WebView redeems it together with the PKCE verifier only
 * it holds, and the cookies land in its own partition.
 *
 * <p>Single use, one minute, only the hash of the code is stored.
 */
@Document(collection = "sso_handoffs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SsoHandoffDocument {

    @Id
    private @Nullable String id;

    @Indexed(unique = true)
    private String codeHash = "";

    private String tenantId = "";

    private String username = "";

    /** Session end of the sign-in ({@code sxp}) — carried into the redeemed tokens. */
    private @Nullable Instant sessionExpiresAt;

    /** S256 challenge of the WebView's verifier. */
    private String appChallenge = "";

    @Indexed(expireAfter = "0s")
    private Instant expiresAt = Instant.now();
}
