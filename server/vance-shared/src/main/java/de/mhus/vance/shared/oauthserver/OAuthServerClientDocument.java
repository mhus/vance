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
 * A client registered through Dynamic Client Registration (RFC 7591) at one
 * tenant's authorization server.
 *
 * <p>Only public clients exist: there is no secret, PKCE is what binds a code
 * to the program that asked for it. Everything descriptive here —
 * {@link #clientName}, {@link #clientUri}, {@link #logoUri} — is what the
 * client <em>said</em> about itself, and the consent page shows it as such.
 * The one fact the server checks is {@link #redirectUris}: an authorization
 * request must name one of them exactly.
 *
 * <p>Clients identified by an HTTPS URL (Client ID Metadata Documents) and the
 * built-in first-party clients have no row here.
 */
@Document(collection = "oauth_server_clients")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthServerClientDocument {

    @Id
    private @Nullable String id;

    /** The issued {@code client_id}. Random, unique across tenants. */
    @Indexed(unique = true)
    private String clientId = "";

    /** Tenant whose authorization server registered the client. */
    @Indexed
    private String tenantId = "";

    private String clientName = "";

    private @Nullable String clientUri;

    private @Nullable String logoUri;

    /** Registered redirect URIs, compared exactly (loopback: all but the port). */
    @Builder.Default
    private List<String> redirectUris = new ArrayList<>();

    private @Nullable Instant createdAt;

    /**
     * Last authorization request naming this client. Drives the cleanup of
     * registrations nobody uses — the registration endpoint is open, so an
     * unused row must not live forever.
     */
    private @Nullable Instant lastUsedAt;
}
