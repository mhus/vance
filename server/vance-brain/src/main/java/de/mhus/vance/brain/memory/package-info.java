/**
 * Brain-side memory orchestration. Wraps the persistence-level
 * {@link de.mhus.vance.shared.memory.MemoryService} with engine
 * policy: when to compact, what to summarize, how to mark sources
 * archived without losing the audit trail.
 */
@NullMarked
package de.mhus.vance.brain.memory;

import org.jspecify.annotations.NullMarked;
