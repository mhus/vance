package de.mhus.vance.brain.oauthserver;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One of the protected resources the OAuth server issues tokens for — an MCP
 * door. The door decides the narrowing of a token, not a free-form scope
 * string: the client names the door as {@code resource} (RFC 8707), and the
 * door brings its scope profile and, for the project door, its pin.
 *
 * @param kind    which door
 * @param project the project of the {@link Kind#MCP_PROJECT} door, else {@code null}
 */
public record OAuthDoor(Kind kind, @Nullable String project) {

    /** OAuth scope of a full-account token — first-party clients only. */
    public static final String ACCOUNT_SCOPE = "account";

    public enum Kind {
        /** {@code /mcp} — the full tool catalogue under the account's grants. */
        MCP("mcp"),
        /** {@code /mcp/{project}} — the regulated document surface of one project. */
        MCP_PROJECT("mcp-project"),
        /** {@code /agents} — the Trillian agents door. */
        AGENTS("agents");

        private final String scope;

        Kind(String scope) {
            this.scope = scope;
        }

        /** OAuth scope string, equal to the integration scope profile id of the door. */
        public String scope() {
            return scope;
        }
    }

    public OAuthDoor {
        if ((kind == Kind.MCP_PROJECT) != (project != null && !project.isBlank())) {
            throw new IllegalArgumentException("project is required for, and only for, the project door");
        }
    }

    public static OAuthDoor mcp() {
        return new OAuthDoor(Kind.MCP, null);
    }

    public static OAuthDoor mcpProject(String project) {
        return new OAuthDoor(Kind.MCP_PROJECT, project);
    }

    public static OAuthDoor agents() {
        return new OAuthDoor(Kind.AGENTS, null);
    }

    public String scope() {
        return kind.scope();
    }

    /** Scope profiles a token for this door carries as {@code scp}. */
    public List<String> scopeProfiles() {
        return List.of(kind.scope());
    }

    /** Path relative to the tenant root: {@code /mcp}, {@code /mcp/{project}}, {@code /agents}. */
    public String path() {
        return switch (kind) {
            case MCP -> "/mcp";
            case MCP_PROJECT -> "/mcp/" + project;
            case AGENTS -> "/agents";
        };
    }
}
