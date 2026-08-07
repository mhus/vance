package de.mhus.vance.foot.permission;

/**
 * The three rule domains of the sandbox. {@link #PATHS} rules use globs
 * and gate the reading/writing {@code client_file_*} tools;
 * {@link #COMMANDS} rules use regex and gate {@code client_exec_run};
 * {@link #DELETE} rules use globs and gate {@code client_file_delete}
 * alone.
 *
 * <p>{@code DELETE} is separate from {@code PATHS} on purpose. Sharing
 * the path rules would mean every allow the user wrote to let the agent
 * <em>read</em> a tree also lets it <em>delete</em> in that tree — a
 * broad {@code ~/projects/**} would silently authorise removing the lot.
 * Deleting therefore needs its own allow rules; deny still cascades from
 * {@code PATHS} (see {@link PermissionPolicy#evaluateDelete}).
 */
public enum PermissionDomain {
    PATHS,
    COMMANDS,
    DELETE
}
