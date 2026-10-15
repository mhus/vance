package de.mhus.vance.brain.tools.mcp.oauth;

import de.mhus.vance.brain.oauth.OAuthTokenOwner;
import de.mhus.vance.brain.oauth.OAuthTokenRefresher;
import de.mhus.vance.brain.tools.types.ToolPackUnavailableException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Secret resolver of the HTTP transport of one MCP server tool with
 * {@code auth.type: oauth}. It answers exactly one value —
 * {@link McpOAuthSettings#BEARER_REFERENCE} on the connector path — with the
 * access token of the account the tool's login scope selects for the
 * calling user and project, straight from {@link OAuthTokenRefresher}
 * (refresh included); everything else goes to the delegate unchanged.
 *
 * <p>This is the only way to a shared project or tenant login's token: the
 * general {@code SettingsSecretResolver} refuses {@code oauth.*} references
 * on the shared scopes. Because the pool attaches this resolver only to the
 * transport of the tool it was built for, and the provider id is derived
 * from that tool's URL, a login's token is only ever sent to the server it
 * was issued for — a tool document cannot point it at another URL.
 */
@Slf4j
public final class McpOAuthBearerResolver implements SecretResolver {

    private final SecretResolver delegate;
    private final OAuthTokenRefresher refresher;
    private final McpOAuthSettings settings;

    McpOAuthBearerResolver(SecretResolver delegate, OAuthTokenRefresher refresher, McpOAuthSettings settings) {
        this.delegate = delegate;
        this.refresher = refresher;
        this.settings = settings;
    }

    /**
     * The resolver for the transport of a tool with {@code parameters}:
     * {@code delegate} itself for any auth but OAuth, else a resolver that
     * also answers the bearer.
     *
     * @throws IllegalStateException for an OAuth tool when no refresher is available
     */
    public static SecretResolver forTool(
            SecretResolver delegate,
            @Nullable OAuthTokenRefresher refresher,
            @Nullable Map<String, Object> parameters) {
        Optional<McpOAuthSettings> settings = McpOAuthSettings.of(parameters);
        if (settings.isEmpty()) {
            return delegate;
        }
        if (refresher == null) {
            throw new IllegalStateException(
                    "MCP server with auth.type 'oauth' needs the brain's OAuth token refresher");
        }
        return new McpOAuthBearerResolver(delegate, refresher, settings.get());
    }

    /** Restricted path: never the bearer — an agent-reachable value does not reach a login's token. */
    @Override
    public @Nullable String substitute(@Nullable String input, ToolInvocationContext ctx) {
        return delegate.substitute(input, ctx);
    }

    @Override
    public @Nullable String substituteForConnector(@Nullable String input, ToolInvocationContext ctx) {
        if (McpOAuthSettings.BEARER_REFERENCE.equals(input)) {
            return accessToken(ctx);
        }
        return delegate.substituteForConnector(input, ctx);
    }

    /**
     * Access token of the login-scope account for {@code ctx};
     * {@code null} when the call lacks the user or project the scope needs.
     * {@code OAuthExpiredException} propagates — it carries the "log in
     * again" signal.
     */
    private @Nullable String accessToken(ToolInvocationContext ctx) {
        String tenant = ctx.tenantId();
        String project = ctx.projectId();
        String user = ctx.userId();
        if (tenant.isBlank()) {
            return null;
        }
        boolean missing =
                switch (settings.loginScope()) {
                    case USER -> user == null || user.isBlank();
                    case PROJECT -> project == null || project.isBlank();
                    case TENANT -> false;
                };
        if (missing) {
            log.warn(
                    "MCP OAuth: no {} in the call context for provider '{}' — sending no token",
                    settings.loginScope() == McpOAuthSettings.LoginScope.USER ? "user" : "project",
                    settings.providerId());
            return null;
        }
        OAuthTokenOwner owner;
        try {
            owner = settings.owner(project == null ? "" : project, user == null ? "" : user);
        } catch (ToolPackUnavailableException e) {
            log.warn("MCP OAuth: {} — sending no token", e.getMessage());
            return null;
        }
        return refresher.resolveAccessTokenFor(tenant, owner, settings.providerId());
    }
}
