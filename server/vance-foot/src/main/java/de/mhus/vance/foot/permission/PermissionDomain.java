package de.mhus.vance.foot.permission;

/**
 * The two rule domains of the sandbox. {@link #PATHS} rules use globs and
 * gate the {@code client_file_*} tools; {@link #COMMANDS} rules use regex
 * and gate {@code client_exec_run}.
 */
public enum PermissionDomain {
    PATHS,
    COMMANDS
}
