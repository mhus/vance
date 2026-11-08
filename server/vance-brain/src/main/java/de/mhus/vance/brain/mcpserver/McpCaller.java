package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.jwt.VanceJwtClaims;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;

/**
 * Who is calling an MCP door, as far as the access configs care: which kind
 * of credential, its id (integration token {@code jti} / OAuth grant id), and
 * the account.
 *
 * @param kind     credential kind
 * @param tokenId  {@code jti} of an integration token or the grant id of an OAuth token, else {@code null}
 * @param username the account behind the credential
 * @param confinedToProject the project the credential is pinned to, or {@code null} — carried into every tool
 *                 call on the door so a tool cannot reach further than the token itself
 */
public record McpCaller(
        Kind kind,
        @Nullable String tokenId,
        @Nullable String username,
        @Nullable String confinedToProject) {

    public McpCaller(Kind kind, @Nullable String tokenId, @Nullable String username) {
        this(kind, tokenId, username, null);
    }

    public enum Kind {
        /** A full-account login token (ACCESS, or OAuth {@code account} scope). */
        ACCOUNT,
        /** An integration token. */
        INTEGRATION,
        /** A narrowed OAuth token issued for an MCP door. */
        OAUTH
    }

    public static McpCaller account(@Nullable String username) {
        return new McpCaller(Kind.ACCOUNT, null, username);
    }

    public static McpCaller integration(@Nullable String username, String tokenId) {
        return new McpCaller(Kind.INTEGRATION, tokenId, username);
    }

    public static McpCaller oauth(@Nullable String username, String grantId) {
        return new McpCaller(Kind.OAUTH, grantId, username);
    }

    /** The caller of an authenticated request, read from the attributes the access filter set. */
    public static McpCaller from(HttpServletRequest request) {
        McpCaller caller = unconfinedFrom(request);
        return new McpCaller(
                caller.kind(),
                caller.tokenId(),
                caller.username(),
                SecurityContextFactory.credentialProjectConfinement(request));
    }

    private static McpCaller unconfinedFrom(HttpServletRequest request) {
        String username = AccessFilterBase.usernameOrNull(request);
        if (!(request.getAttribute(AccessFilterBase.ATTR_CLAIMS) instanceof VanceJwtClaims claims)) {
            return account(username);
        }
        if (claims.tokenType() == TokenType.INTEGRATION && claims.tokenId() != null) {
            return integration(username, claims.tokenId());
        }
        if (claims.tokenType() == TokenType.OAUTH
                && claims.tokenId() != null
                && !claims.scopeProfiles().isEmpty()) {
            return oauth(username, claims.tokenId());
        }
        return account(username);
    }

    /** Whether the credential is narrowed (integration token or door-bound OAuth token). */
    public boolean narrowed() {
        return kind != Kind.ACCOUNT;
    }
}
