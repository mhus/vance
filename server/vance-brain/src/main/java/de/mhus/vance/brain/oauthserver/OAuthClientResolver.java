package de.mhus.vance.brain.oauthserver;

import de.mhus.vance.shared.oauthserver.OAuthServerClientDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerClientService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns a {@code client_id} into a {@link ResolvedOAuthClient}: one of the
 * built-in first-party clients, an HTTPS URL to a Client ID Metadata
 * Document, or a dynamically registered client of the tenant.
 */
@Component
@RequiredArgsConstructor
public class OAuthClientResolver {

    /** The foot CLI — logs in through the browser with a loopback redirect (RFC 8252). */
    public static final String FOOT_CLIENT_ID = "vancetope-foot";

    /**
     * Built-in clients. Fixed in code, not registrable: they are the only
     * clients that may hold the full-account {@code account} scope, so their
     * redirect URIs must not be something a stranger can choose.
     *
     * <p>The foot CLI redirects to a loopback <em>IP literal</em> (RFC 8252
     * §7.3, any port): {@code localhost} is deliberately not registered — the
     * name can resolve elsewhere (hosts file, resolver) and the listener
     * would not be the one bound on the loopback interface.
     */
    static final Map<String, ResolvedOAuthClient> FIRST_PARTY = Map.of(
            FOOT_CLIENT_ID,
            new ResolvedOAuthClient(
                    FOOT_CLIENT_ID,
                    OAuthClientKind.FIRST_PARTY,
                    "Vancetope CLI",
                    null,
                    List.of("http://127.0.0.1/callback", "http://[::1]/callback")));

    private final OAuthServerClientService clientService;
    private final ClientMetadataDocumentResolver documentResolver;
    private final OAuthServerSettings settings;

    /**
     * @throws InvalidClientException when the client is unknown or its metadata unacceptable
     */
    public ResolvedOAuthClient resolve(String tenant, String clientId) {
        ResolvedOAuthClient firstParty = FIRST_PARTY.get(clientId);
        if (firstParty != null) {
            return firstParty;
        }
        if (ClientMetadataDocumentResolver.isDocumentClientId(clientId)) {
            if (!settings.clientMetadataDocumentsEnabled()) {
                throw new InvalidClientException("client metadata documents are not enabled on this server");
            }
            return documentResolver.resolve(clientId);
        }
        OAuthServerClientDocument registered =
                clientService.find(tenant, clientId).orElseThrow(() -> new InvalidClientException("unknown client_id"));
        return new ResolvedOAuthClient(
                registered.getClientId(),
                OAuthClientKind.DCR,
                registered.getClientName(),
                registered.getClientUri(),
                registered.getRedirectUris());
    }
}
