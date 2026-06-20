/**
 * WorkTarget layer — per-process state plus the generic
 * {@code file_*} / {@code exec_*} wrapper tools that dispatch to
 * either {@code client_*} (Foot CLI) or {@code work_*} (Brain-server
 * workspace RootDir) backends.
 *
 * <p>Persistence + value object live in
 * {@link de.mhus.vance.shared.worktarget}. This package adds the
 * Brain-side service + tools.
 *
 * <p>See {@code planning/work-target-and-tool-rename.md}.
 */
@NullMarked
package de.mhus.vance.brain.tools.worktarget;

import org.jspecify.annotations.NullMarked;
