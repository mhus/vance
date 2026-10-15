package de.mhus.vance.brain.oauth;

import de.mhus.vance.toolpack.core.PackHttpClient;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * OAuth flow against the authorization server of a remote MCP server
 * (MCP specification, "Authorization"). A generic OAuth 2.0 code flow with
 * PKCE plus the RFC 8707 {@code resource} parameter — the canonical
 * resource of the MCP server from its protected-resource metadata — on the
 * authorize request, the code exchange and the refresh, so the issued
 * tokens are bound to exactly that server.
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
 *     mcpUrl: "https://mcp.example.com/mcp"   # the configured server URL
 *     resource: "https://mcp.example.com/mcp" # RFC 9728 resource, sent as RFC 8707 resource
 *     issuer: "https://auth.example.com"      # RFC 8414 issuer, checked against RFC 9207 iss
 *     issParameterSupported: false
 *     usePkce: true
 *     registration: cimd | dcr | manual
 *     title: "Example workspace"
 *     managed: true
 *     tls:                                    # the tool's tls block, see #tlsFor
 *       skipVerification: false
 *       trustedCaPemPath: "/etc/ca.pem"
 * </pre>
 */
@Component
public class McpOAuthProvider extends GenericOAuth2Provider {

    public static final String TYPE_ID = "mcp";

    /** Extra key: URL of the MCP server as configured on the tool. */
    public static final String EXTRA_MCP_URL = "mcpUrl";

    /**
     * Extra key: the canonical resource identifier from the server's
     * protected-resource metadata (RFC 9728 {@code resource}), sent as the
     * RFC 8707 {@code resource} parameter. Falls back to {@link #EXTRA_MCP_URL}.
     */
    public static final String EXTRA_RESOURCE = "resource";

    /** Extra key: the tool's {@code tls} block ({@code skipVerification}, {@code trustedCaPemPath}). */
    public static final String EXTRA_TLS = "tls";

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
        String resource = resource(cfg);
        if (resource != null) {
            params.put("resource", resource);
        }
    }

    @Override
    protected void decorateTokenParams(OAuthProviderConfig cfg, Map<String, String> form) {
        String resource = resource(cfg);
        if (resource != null) {
            form.put("resource", resource);
        }
    }

    /**
     * The tool's TLS settings, narrowed to what may apply to {@code url}:
     * {@code trustedCaPemPath} carries over to the authorization server
     * (a private CA usually signs both), {@code skipVerification} only to
     * the MCP server's own origin — an authorization server on another
     * origin was named by a foreign response and is always verified.
     */
    @Override
    @SuppressWarnings("unchecked")
    protected PackHttpClient.TlsConfig tlsFor(OAuthProviderConfig cfg, String url) {
        Object raw = cfg.extra().get(EXTRA_TLS);
        if (!(raw instanceof Map<?, ?> tls)) {
            return PackHttpClient.TlsConfig.DEFAULT;
        }
        String mcpUrl = mcpUrl(cfg);
        return endpointTls(
                PackHttpClient.TlsConfig.fromMap((Map<String, Object>) tls), mcpUrl == null ? "" : mcpUrl, url);
    }

    /**
     * TLS settings of the MCP server tool applied to a request to
     * {@code endpointUrl}: unchanged on the MCP server's origin; elsewhere
     * only the trusted CA, never {@code skipVerification}.
     */
    public static PackHttpClient.TlsConfig endpointTls(
            PackHttpClient.TlsConfig toolTls, String mcpUrl, String endpointUrl) {
        if (!toolTls.skipVerification() || sameOrigin(mcpUrl, endpointUrl)) {
            return toolTls;
        }
        return new PackHttpClient.TlsConfig(false, toolTls.trustedCaPem());
    }

    /** Whether two absolute URLs share scheme, host and (effective) port. */
    public static boolean sameOrigin(String a, String b) {
        String oa = origin(a);
        return oa != null && oa.equals(origin(b));
    }

    private static @Nullable String origin(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : -1;
        }
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** The RFC 8707 {@code resource} of an {@code mcp} provider config. */
    public static @Nullable String resource(OAuthProviderConfig cfg) {
        Object v = cfg.extra().get(EXTRA_RESOURCE);
        return v instanceof String s && !s.isBlank() ? s : mcpUrl(cfg);
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
