package de.mhus.vance.brain.oauth;

import de.mhus.vance.shared.home.HomeBootstrapService;

/**
 * Whose OAuth tokens these are — and therefore where they are stored.
 * Tokens always live as {@code oauth.<providerId>.*} settings on one
 * project: the user's hub for a personal login, the project itself for a
 * project-wide account, the {@code _tenant} project for a tenant-wide one.
 *
 * <p>Every provider except MCP servers only ever uses {@link Kind#USER};
 * an MCP server tool picks its owner through {@code auth.loginScope}.
 *
 * @param kind             the scope of the login
 * @param storageProjectId the project whose settings hold the tokens
 */
public record OAuthTokenOwner(Kind kind, String storageProjectId) {

    public enum Kind {
        USER,
        PROJECT,
        TENANT
    }

    public static OAuthTokenOwner user(String userId) {
        return new OAuthTokenOwner(Kind.USER, HomeBootstrapService.hubProjectName(userId));
    }

    /**
     * The account of project {@code projectId}.
     *
     * @throws IllegalArgumentException for {@code _tenant} — its storage is
     *         the tenant account's, so a "project login" there would silently
     *         be the tenant login (no fallback, see {@link #tenant()})
     */
    public static OAuthTokenOwner project(String projectId) {
        if (HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            throw new IllegalArgumentException("'" + projectId + "' has no project account — "
                    + "a project login needs a real project; use the tenant login for a tenant-wide account");
        }
        return new OAuthTokenOwner(Kind.PROJECT, projectId);
    }

    public static OAuthTokenOwner tenant() {
        return new OAuthTokenOwner(Kind.TENANT, HomeBootstrapService.TENANT_PROJECT_NAME);
    }

    /**
     * Owner of tokens stored on {@code storageProjectId}, as persisted on an
     * OAuth state. The kind is derived from the project name.
     */
    public static OAuthTokenOwner ofStorageProject(String storageProjectId) {
        if (HomeBootstrapService.TENANT_PROJECT_NAME.equals(storageProjectId)) {
            return tenant();
        }
        if (storageProjectId.startsWith(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX)) {
            return new OAuthTokenOwner(Kind.USER, storageProjectId);
        }
        return project(storageProjectId);
    }

    /** Whether several users share this login (project or tenant account). */
    public boolean shared() {
        return kind != Kind.USER;
    }
}
