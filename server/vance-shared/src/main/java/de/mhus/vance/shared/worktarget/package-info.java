/**
 * Per-process WorkTarget — describes where generic {@code file_*}
 * and {@code exec_*} tools dispatch to. Two surfaces today:
 * {@link WorkTargetKind#CLIENT} (user's local machine via Foot CLI)
 * and {@link WorkTargetKind#WORK} (Brain-server-side workspace
 * RootDir). Persisted on {@code ThinkProcessDocument.engineParams.workTarget}
 * as a Map; service lives in
 * {@code de.mhus.vance.brain.tools.worktarget}.
 *
 * <p>See {@code planning/work-target-and-tool-rename.md}.
 */
@NullMarked
package de.mhus.vance.shared.worktarget;

import org.jspecify.annotations.NullMarked;
