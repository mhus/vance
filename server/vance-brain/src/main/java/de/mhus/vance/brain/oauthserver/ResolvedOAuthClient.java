package de.mhus.vance.brain.oauthserver;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A client as the authorization endpoint sees it, whatever its origin.
 *
 * @param clientId     the {@code client_id} as presented
 * @param kind         first-party, dynamically registered, or metadata document
 * @param clientName   display name (self-asserted except for first-party)
 * @param clientUri    homepage, if any
 * @param redirectUris allowed redirect URIs, compared by {@link RedirectUris#matches}
 */
public record ResolvedOAuthClient(
        String clientId,
        OAuthClientKind kind,
        String clientName,
        @Nullable String clientUri,
        List<String> redirectUris) {

    public ResolvedOAuthClient {
        redirectUris = List.copyOf(redirectUris);
    }

    public boolean firstParty() {
        return kind == OAuthClientKind.FIRST_PARTY;
    }

    public boolean allowsRedirect(String redirectUri) {
        return redirectUris.stream().anyMatch(registered -> RedirectUris.matches(registered, redirectUri));
    }
}
