package de.mhus.vance.shared.workspace;

/**
 * Whether this pod offers tenants access to its workspace
 * ({@code vance.workspace.mode}, {@code planning/multi-tenant-isolation.md} part 2).
 * Operator config, not a setting.
 *
 * <p>{@code OFF} switches off the <em>access</em> — tools that write or read
 * arbitrary files or start programs, exec jobs, compose runs — not the mechanics:
 * {@link WorkspaceService}, the handlers, the script library store and its
 * persistence keep working.
 */
public enum WorkspaceMode {
    /** Single-tenant / development: the workspace and its tools as they are. */
    LOCAL,
    /** Shared pod: no workspace access for tenants. */
    OFF;

    public boolean accessible() {
        return this == LOCAL;
    }
}
