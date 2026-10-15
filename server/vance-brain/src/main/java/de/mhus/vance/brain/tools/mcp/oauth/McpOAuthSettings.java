package de.mhus.vance.brain.tools.mcp.oauth;

import de.mhus.vance.brain.oauth.OAuthTokenOwner;
import de.mhus.vance.toolpack.mcp.McpConfig;
import de.mhus.vance.toolpack.rest.AuthSpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The {@code auth} block of an {@code mcp_server} tool with
 * {@code type: oauth}:
 *
 * <pre>
 *   parameters:
 *     url: "https://mcp.example.com/mcp"
 *     auth:
 *       type: oauth
 *       loginScope: user          # user | project | tenant — whose account
 *       scopes: [ "read" ]        # optional; else from the server's challenge / metadata
 *       clientId: "…"             # optional; only for servers without self-registration
 *       clientSecret: "{{secret:tenant:example.client_secret}}"   # optional, with clientId
 * </pre>
 *
 * @param url          the MCP server URL — the protected resource
 * @param loginScope   whose account the server is used with
 * @param scopes       OAuth scopes to request; empty = let discovery decide
 * @param clientId     a client id registered by hand, or {@code null} for self-registration
 * @param clientSecret secret reference/value for {@code clientId}, or {@code null}
 */
public record McpOAuthSettings(
        String url,
        LoginScope loginScope,
        List<String> scopes,
        @Nullable String clientId,
        @Nullable String clientSecret) {

    /** Whose account an OAuth-protected MCP server is used with. */
    public enum LoginScope {
        /** Every user logs in with their own account (default). */
        USER,
        /** One account for everybody working in the project the tool is called in. */
        PROJECT,
        /** One account for the whole tenant. */
        TENANT;

        static LoginScope parse(@Nullable Object raw) {
            if (raw == null || (raw instanceof String s && s.isBlank())) return USER;
            return switch (String.valueOf(raw).trim().toLowerCase(Locale.ROOT)) {
                case "user" -> USER;
                case "project" -> PROJECT;
                case "tenant" -> TENANT;
                default ->
                    throw new IllegalArgumentException(
                            "auth.loginScope '" + raw + "' — expected: user, project, tenant");
            };
        }

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Provider-id prefix of every brain-managed MCP OAuth provider. */
    public static final String PROVIDER_ID_PREFIX = "mcp-";

    /**
     * The settings of {@code parameters} when its {@code auth.type} is
     * {@code oauth}; empty for any other auth.
     *
     * @throws IllegalArgumentException when the oauth block is malformed
     */
    public static Optional<McpOAuthSettings> of(@Nullable Map<String, Object> parameters) {
        if (parameters == null) return Optional.empty();
        if (!(parameters.get("auth") instanceof Map<?, ?> auth)) return Optional.empty();
        if (!(auth.get("type") instanceof String type) || !"oauth".equalsIgnoreCase(type.trim())) {
            return Optional.empty();
        }
        if (!(parameters.get("url") instanceof String url) || url.isBlank()) {
            throw new IllegalArgumentException("auth.type 'oauth' needs an HTTP MCP server with 'url'");
        }
        String clientId = stringOrNull(auth.get("clientId"));
        String clientSecret = stringOrNull(auth.get("clientSecret"));
        if (clientSecret != null && clientId == null) {
            throw new IllegalArgumentException("auth.clientSecret needs auth.clientId");
        }
        return Optional.of(new McpOAuthSettings(
                url.trim(),
                LoginScope.parse(auth.get("loginScope")),
                scopes(auth.get("scopes")),
                clientId,
                clientSecret));
    }

    /**
     * Provider id for this server: {@code mcp-<host>-<hash>}. Derived from the
     * URL, not from the tool name — the same server configured in several
     * projects shares one login and one client registration; tokens are bound
     * to the URL anyway (RFC 8707 {@code resource}).
     */
    public String providerId() {
        return providerIdFor(url);
    }

    /** See {@link #providerId()}. */
    public static String providerIdFor(String url) {
        String host;
        try {
            host = URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        String slug = host == null ? "server" : host.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        if (slug.length() > 40) slug = slug.substring(0, 40);
        return PROVIDER_ID_PREFIX + (slug.isEmpty() ? "server" : slug) + "-" + hash8(url.trim());
    }

    /** Whose tokens a call in {@code projectId} by {@code userId} uses. */
    public OAuthTokenOwner owner(String projectId, String userId) {
        return switch (loginScope) {
            case USER -> OAuthTokenOwner.user(userId);
            case PROJECT -> OAuthTokenOwner.project(projectId);
            case TENANT -> OAuthTokenOwner.tenant();
        };
    }

    /**
     * The transport config of an {@code mcp_server} tool, with
     * {@code auth.type: oauth} translated into the bearer token of the
     * login scope's account.
     */
    public static McpConfig effectiveConfig(@Nullable Map<String, Object> parameters) {
        McpConfig cfg = McpConfig.fromParameters(parameters);
        if (cfg.auth().type() != AuthSpec.Type.OAUTH) {
            return cfg;
        }
        McpOAuthSettings settings =
                of(parameters).orElseThrow(() -> new IllegalArgumentException("auth.type 'oauth' block is incomplete"));
        return cfg.withAuth(AuthSpec.bearer(settings.tokenTemplate()));
    }

    /** The secret template the MCP transport sends as bearer token. */
    public String tokenTemplate() {
        return "{{secret:" + loginScope.wireName() + ":oauth." + providerId() + ".access_token}}";
    }

    private static String hash8(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static List<String> scopes(@Nullable Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof String s) {
            for (String part : s.trim().split("\\s+")) {
                if (!part.isBlank()) out.add(part);
            }
        } else if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) out.add(s.trim());
            }
        }
        return out;
    }

    private static @Nullable String stringOrNull(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
