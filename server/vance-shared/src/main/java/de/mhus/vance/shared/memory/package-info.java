/**
 * Engine-side persistent memory.
 *
 * <p>{@link de.mhus.vance.shared.memory.MemoryDocument} stores
 * artefacts that outlive a single chat turn — compaction summaries
 * (with refs back to the originals so the trail is auditable),
 * scratchpad notes, plans, derived insights. Scope is tenant-anchored
 * with optional project / session / process narrowing, so the same
 * collection can hold project-wide knowledge and process-private
 * scratch alike.
 */
@NullMarked
package de.mhus.vance.shared.memory;

import org.jspecify.annotations.NullMarked;
