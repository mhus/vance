package de.mhus.vance.brain.oauth;

import de.mhus.vance.toolpack.core.PackHttpClient;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * OAuth flow against the authorization server of a remote MCP server
 * (MCP specification, "Authorization"). A generic OAuth 2.0 code flow with
 * PKCE plus the RFC 8707 {@code resource} parameter — the MCP server's URL —
 * on the authorize request, the code exchange and the refresh, so the
 * issued tokens are bound to exactly that server.
 *
 * <p>Provider documents of this type are written by the brain, not by
 * hand: the first login of an {@code mcp_server} tool with
 * {@code auth.type: oauth} discovers the endpoints and registers a client
 * ({@code McpOAuthService}). Layout of {@code _vance/oauth/<id>.yaml}:
 *
 * <pre>
 *   type: mcp
 *   clientId: "…"               # CIMD URL, DCR result or configured by hand
 *   authorizeUrl: "…"           # from the authorization-server metadata
 *   tokenUrl: "…"
 *   scopes: [ … ]
 *   extra:
 *     mcpUrl: "https://mcp.example.com/mcp"   # the protected resource
 *     usePkce: true
 *     registration: cimd | dcr | manual
 *     title: "Example workspace"
 *     managed: true
 * </pre>
 */
@Component
public class McpOAuthProvider extends GenericOAuth2Provider {

    public static final String TYPE_ID = "mcp";

    /** Extra key: URL of the MCP server, sent as {@code resource}. */
    public static final String EXTRA_MCP_URL = "mcpUrl";

    /** Extra key: how the client id was obtained ({@code cimd}, {@code dcr}, {@code manual}). */
    public static final String EXTRA_REGISTRATION = "registration";

    /** Extra key: display name for Connected Accounts. */
    public static final String EXTRA_TITLE = "title";

    /** Extra key: {@code true} when the brain created the document. */
    public static final String EXTRA_MANAGED = "managed";

    public McpOAuthProvider() {
        super();
    }

    McpOAuthProvider(PackHttpClient httpClient) {
        super(httpClient);
    }

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    protected void decorateAuthorizeParams(OAuthProviderConfig cfg, OAuthInitContext ctx, Map<String, String> params) {
        String resource = mcpUrl(cfg);
        if (resource != null) {
            params.put("resource", resource);
        }
    }

    @Override
    protected void decorateTokenParams(OAuthProviderConfig cfg, Map<String, String> form) {
        String resource = mcpUrl(cfg);
        if (resource != null) {
            form.put("resource", resource);
        }
    }

    /** The MCP server URL of an {@code mcp} provider config. */
    public static @Nullable String mcpUrl(OAuthProviderConfig cfg) {
        Object v = cfg.extra().get(EXTRA_MCP_URL);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    /** Display title of a provider, when its config carries one. */
    public static @Nullable String title(OAuthProviderConfig cfg) {
        Object v = cfg.extra().get(EXTRA_TITLE);
        return v instanceof String s && !s.isBlank() ? s : null;
    }
}
