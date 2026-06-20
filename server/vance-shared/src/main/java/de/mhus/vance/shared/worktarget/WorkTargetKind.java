package de.mhus.vance.shared.worktarget;

/**
 * Where the generic {@code file_*} / {@code exec_*} tools dispatch
 * to. Two surfaces today:
 *
 * <ul>
 *   <li>{@link #CLIENT} — the user's local host, accessed via the
 *       Foot CLI. Backend tools: {@code client_file_*},
 *       {@code client_exec_*}. The Foot client must be connected
 *       to the session; if disconnected the dispatch fails with a
 *       clear error.</li>
 *   <li>{@link #WORK} — a project workspace RootDir on the Brain
 *       server. Backend tools: {@code work_file_*}, {@code work_exec_*}.
 *       The {@code dirName} field of {@link WorkTarget} picks which
 *       RootDir; {@code null} means the per-process temp RootDir
 *       (lazy-created by {@code WorkspaceService}).</li>
 * </ul>
 */
public enum WorkTargetKind {
    CLIENT,
    WORK
}
