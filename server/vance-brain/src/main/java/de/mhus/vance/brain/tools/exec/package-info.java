/**
 * Shell exec tool.
 *
 * <p>{@link de.mhus.vance.brain.tools.exec.ExecManager} runs commands
 * in background threads, pumps stdout/stderr to on-disk log files
 * while also buffering them in memory for quick inline responses.
 * Jobs are session-indexed: the {@link
 * de.mhus.vance.brain.tools.exec.ExecRunTool} and its companions only
 * surface jobs started by the caller's own session.
 *
 * <p>CWD of every command is the session workspace dir — so
 * {@code git clone …} lands inside the workspace and the
 * {@code workspace_*} tools can read the results.
 *
 * <p>Persistence is pod-lifetime: log files survive a restart, the
 * live process does not. The manager marks orphaned-on-disk jobs as
 * {@code FAILED} (they have no attached process to reconnect to) —
 * callers must re-submit long-running work after a pod restart.
 */
@NullMarked
package de.mhus.vance.brain.tools.exec;

import org.jspecify.annotations.NullMarked;
