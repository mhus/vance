/**
 * Brain context-assembly support. Sits next to
 * {@link de.mhus.vance.brain.memory} but covers the read-side caching
 * + dedup that drives per-turn prompt build: which resource has been
 * pulled into context already, which auto-attachment has been
 * delivered exactly once.
 *
 * <p>See {@code planning/brain-context-assembler.md} for the full
 * design. v1 only ships {@link ReadStateService}; the
 * {@code composeReminders} / {@code composeAttachments} layer that
 * uses it lives in {@code MemoryContextLoader} and will come later.
 */
@NullMarked
package de.mhus.vance.brain.context;

import org.jspecify.annotations.NullMarked;
