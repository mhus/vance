package de.mhus.vance.brain.oauth;

/**
 * Pairs a tenant's {@link OAuthProviderConfig} with the
 * {@link OAuthProvider} bean implementing its {@code typeId}.
 * Produced by {@link OAuthConfigRegistry#resolve} and consumed by the
 * OAuth controller / token refresher — they call the bean methods,
 * passing the config back in.
 */
public record ResolvedOAuthProvider(
        OAuthProviderConfig config,
        OAuthProvider provider) {

    public String providerId() {
        return config.providerId();
    }

    public String typeId() {
        return config.typeId();
    }
}
