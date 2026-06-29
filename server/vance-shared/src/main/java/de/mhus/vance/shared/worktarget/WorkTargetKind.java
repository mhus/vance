package de.mhus.vance.shared.worktarget;

/**
 * Where the generic {@code file_*} / {@code exec_*} tools dispatch
 * to. Three surfaces today:
 *
 * <ul>
 *   <li>{@link #CLIENT} — the user's local host, accessed via the
 *       Foot CLI bound to the current session. Backend tools:
 *       {@code client_file_*}, {@code client_exec_*}. The Foot client
 *       must be connected to the session; if disconnected the dispatch
 *       fails with a clear error.</li>
 *   <li>{@link #WORK} — a project workspace RootDir on the Brain
 *       server. Backend tools: {@code work_file_*}, {@code work_exec_*}.
 *       The {@code targetName} field of {@link WorkTarget} picks which
 *       RootDir; {@code null} means the per-process temp RootDir
 *       (lazy-created by {@code WorkspaceService}).</li>
 *   <li>{@link #DAEMON} — a named {@code profile=daemon} Foot client
 *       registered in the same project (see {@code DaemonRegistry}).
 *       Routes the {@code client_*} backend tools over the daemon's
 *       WebSocket instead of the session-bound Foot. The
 *       {@code targetName} field carries the required daemon name;
 *       the dispatch fails with a clear error when no such daemon is
 *       online in the project.</li>
 * </ul>
 */
public enum WorkTargetKind {
    CLIENT,
    WORK,
    DAEMON
}
