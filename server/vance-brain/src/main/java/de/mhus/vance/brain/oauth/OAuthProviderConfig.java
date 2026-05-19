package de.mhus.vance.brain.oauth;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Per-tenant configuration of one OAuth provider instance. Produced
 * by {@code OAuthProviderLoader} from a YAML document at
 * {@code oauth/<providerId>.yaml}. The {@code clientSecret} is already
 * resolved from its {@code {{secret:tenant:…}}} reference — consumers
 * see plaintext.
 *
 * <p>{@code typeId} routes to the {@link OAuthProvider} bean that
 * implements the flow. {@code discoveryUrl} is consulted by the OIDC
 * bean only; {@code authorizeUrl}/{@code tokenUrl} are used by
 * generic-OAuth-2.0 beans (and may be set alongside discovery to
 * override the discovered endpoints in edge cases).
 *
 * @param providerId   stable id, used as YAML stem and as part of the
 *                     user-settings-key prefix
 *                     ({@code oauth.<providerId>.access_token})
 * @param typeId       routes to {@link OAuthProvider#typeId()}; values
 *                     like {@code oidc}, {@code generic-oauth2},
 *                     {@code slack}, {@code atlassian}, {@code google}
 * @param discoveryUrl OpenID-Connect Discovery URL ({@code oidc} only)
 * @param authorizeUrl OAuth-Authorize-Endpoint (generic-oauth2)
 * @param tokenUrl     OAuth-Token-Endpoint (generic-oauth2)
 * @param clientId     OAuth-App-Identifier registered with the provider
 * @param clientSecret resolved plaintext secret — never log this
 * @param scopes       scopes to request at authorize-time
 * @param extra        provider-specific knobs (e.g. Slack workspace id)
 */
public record OAuthProviderConfig(
        String providerId,
        String typeId,
        @Nullable String discoveryUrl,
        @Nullable String authorizeUrl,
        @Nullable String tokenUrl,
        String clientId,
        String clientSecret,
        List<String> scopes,
        Map<String, Object> extra) {

    @Override
    public String toString() {
        // Excludes clientSecret to keep it out of accidental log lines.
        // Use the explicit accessor when debugging requires the value.
        return "OAuthProviderConfig{"
                + "providerId='" + providerId + '\''
                + ", typeId='" + typeId + '\''
                + ", discoveryUrl=" + discoveryUrl
                + ", authorizeUrl=" + authorizeUrl
                + ", tokenUrl=" + tokenUrl
                + ", clientId='" + clientId + '\''
                + ", clientSecret=***"
                + ", scopes=" + scopes
                + ", extra=" + extra
                + '}';
    }
}
