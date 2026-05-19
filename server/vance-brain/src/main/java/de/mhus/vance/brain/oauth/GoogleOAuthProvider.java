package de.mhus.vance.brain.oauth;

import de.mhus.vance.toolpack.core.PackHttpClient;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Google OAuth 2.0 — sits on the OIDC base (Google ships a well-known
 * Discovery document) but needs two extra authorize-URL parameters to
 * issue a refresh token at all:
 *
 * <ul>
 *   <li>{@code access_type=offline} — without this Google returns only
 *       an access token, no refresh. Background tool use then breaks
 *       after ~1h.</li>
 *   <li>{@code prompt=consent} — re-prompts the consent screen on every
 *       connect. Without it, a re-connect after an earlier session
 *       silently <i>omits</i> the refresh token. Forcing consent
 *       guarantees a fresh refresh token on every "Connect" click.</li>
 * </ul>
 *
 * <p>Beyond the authorize-URL tweak, the flow is plain OIDC.
 * Discovery is normally
 * {@code https://accounts.google.com/.well-known/openid-configuration}.
 */
@Component
public class GoogleOAuthProvider extends OidcProvider {

    /** Bean-type identifier. */
    public static final String TYPE_ID = "google";

    public GoogleOAuthProvider() {
        super();
    }

    GoogleOAuthProvider(PackHttpClient httpClient) {
        super(httpClient);
    }

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    protected void decorateAuthorizeParams(
            OAuthProviderConfig cfg, OAuthInitContext ctx, Map<String, String> params) {
        // Both flags are required to get a refresh token on every connect.
        // Tenants who really want the no-refresh behaviour can override by
        // passing extra.access_type / extra.prompt in their YAML, but the
        // OAuth-Refresher then surfaces OAuthExpiredException after 1h.
        params.putIfAbsent("access_type", "offline");
        params.putIfAbsent("prompt", "consent");
        // Allow YAML 'extra' to inject additional params (e.g. include_granted_scopes=true).
        for (Map.Entry<String, Object> e : cfg.extra().entrySet()) {
            if (e.getValue() != null) {
                params.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
    }
}
