/**
 * Per-process WorkTarget — describes where generic {@code file_*}
 * and {@code exec_*} tools dispatch to. Three surfaces today:
 * {@link WorkTargetKind#CLIENT} (user's local machine via the
 * session-bound Foot CLI), {@link WorkTargetKind#WORK} (Brain-server-side
 * workspace RootDir) and {@link WorkTargetKind#DAEMON} (a named
 * {@code profile=daemon} Foot registered in the same project). The
 * kind-dependent qualifier (RootDir name resp. daemon name) lives in
 * the single {@code targetName} field. Persisted on
 * {@code ThinkProcessDocument.engineParams.workTarget} as a Map;
 * service lives in {@code de.mhus.vance.brain.tools.worktarget}.
 *
 * <p>See {@code planning/work-target-and-tool-rename.md}.
 */
@NullMarked
package de.mhus.vance.shared.worktarget;

import org.jspecify.annotations.NullMarked;
