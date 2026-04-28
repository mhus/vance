/**
 * Per-user Vance Hub bootstrap — ensures the {@code Home} project group and
 * the per-user {@code vance-<login>} {@link de.mhus.vance.shared.project.ProjectKind#SYSTEM}
 * project exist before a hub session can be opened. See
 * {@code specification/vance-engine.md} §2.
 */
@NullMarked
package de.mhus.vance.shared.home;

import org.jspecify.annotations.NullMarked;
