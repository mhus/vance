/**
 * Scratchpad tools — named-slot persistent notes for an engine to keep
 * across turns. All process-scoped via {@code ctx.processId()}; the
 * slots are stored as {@link de.mhus.vance.shared.memory.MemoryKind#SCRATCHPAD}
 * memory entries with supersede chains, so the audit trail is intact
 * even after a slot is overwritten or deleted.
 *
 * <p>All four tools are <em>secondary</em> by design — they exist for
 * engines that take notes (Arthur, Frankie, Ford, future planners). The
 * LLM discovers them via {@code tool_list} when it needs them and they
 * stay out of the prompt otherwise.
 *
 * <p>That takes <b>both</b> flags: {@code primary() == false} governs
 * engines with no allow-list and no recipe filter, {@code deferred() ==
 * true} governs everything else — {@code ContextToolsApi.classify} reads
 * {@code deferred()} and never asks {@code primary()}. Setting only the
 * first is the trap: on a restricted engine the tools would ship their
 * full schema in every turn, which is exactly what the allow-list exists
 * to prevent.
 */
@NullMarked
package de.mhus.vance.brain.tools.scratchpad;

import org.jspecify.annotations.NullMarked;
