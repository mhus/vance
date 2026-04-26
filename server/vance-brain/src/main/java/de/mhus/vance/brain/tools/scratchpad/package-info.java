/**
 * Scratchpad tools — named-slot persistent notes for an engine to keep
 * across turns. All process-scoped via {@code ctx.processId()}; the
 * slots are stored as {@link de.mhus.vance.shared.memory.MemoryKind#SCRATCHPAD}
 * memory entries with supersede chains, so the audit trail is intact
 * even after a slot is overwritten or deleted.
 *
 * <p>All four tools are <em>secondary</em> by design — they exist for
 * engines that take notes (Arthur, future planners). The LLM
 * discovers them via {@code find_tools} when it needs them and they
 * stay out of the prompt otherwise.
 */
@NullMarked
package de.mhus.vance.brain.tools.scratchpad;

import org.jspecify.annotations.NullMarked;
