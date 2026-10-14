package de.mhus.vance.shared.oauthserver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An authorization request that passed validation and now waits for the
 * user — to log in, and (for third-party clients) to approve it on the
 * consent page.
 *
 * <p>Everything the eventual code is bound to is fixed here at validation
 * time: client, exact redirect URI, PKCE challenge, resource and the
 * narrowing derived from it. The browser only carries the opaque
 * {@link #requestId} between the steps, so nothing the user agent sends later
 * can change what the request asks for.
 *
 * <p>{@link #userId} is bound the first time a logged-in user looks at the
 * request; the consent page then gets a one-time ticket, stored here only as
 * {@link #consentTicketHash}. The consent POST must present both the session
 * and the ticket, and takes the request out of the collection atomically —
 * a decision happens once. TTL-evicted.
 */
@Document(collection = "oauth_server_requests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthServerAuthRequestDocument {

    @Id
    private @Nullable String id;

    @Indexed(unique = true)
    private String requestId = "";

    private String tenantId = "";

    private String clientId = "";

    /** Self-asserted name, snapshot for the consent page and the grant. */
    private String clientName = "";

    /** {@code dcr}, {@code cimd} or {@code first-party}. */
    private String clientKind = "";

    private @Nullable String clientUri;

    /** The exact redirect URI the code goes to. */
    private String redirectUri = "";

    /** Client state, echoed back; at most {@code OAuthServerAuthorizationService.MAX_STATE_LENGTH} characters. */
    private @Nullable String state;

    /** PKCE S256 challenge. */
    private String codeChallenge = "";

    /** Canonical resource URL, {@code null} for the {@code account} scope. */
    private @Nullable String resource;

    private String scope = "";

    @Builder.Default
    private List<String> scopeProfiles = new ArrayList<>();

    private @Nullable String projectId;

    /** The user the request is bound to, once one logged in and looked at it. */
    private @Nullable String userId;

    /**
     * SHA-256 of the one-time value the consent POST must echo. Re-issued on
     * every consent page view of the bound user; only the latest is valid.
     */
    private @Nullable String consentTicketHash;

    private @Nullable Instant createdAt;

    @Indexed(expireAfterSeconds = 0)
    private Instant expiresAt = Instant.now();
}
