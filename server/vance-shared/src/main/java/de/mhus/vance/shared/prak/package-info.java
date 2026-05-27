/**
 * Prak — wire types for the memory-evaluation pipeline.
 *
 * <p>Prak (Hitchhiker character — "Prak speaks the truth") is Vance's
 * truth-speaker: an LLM-driven classifier that reads conversation
 * spans and emits {@link de.mhus.vance.shared.prak.ExtractedItem}s for
 * downstream consumers (span-strength, memory promotion, conflict
 * surfacing). The records in this package are the on-the-wire shape
 * between analyzer and consumer — no service logic, no Mongo mapping.
 * See {@code planning/memory-evaluation-pipeline.md} for the design.
 *
 * <p>Brain-side services that produce, validate and apply these
 * records live in {@code de.mhus.vance.brain.prak}.
 */
@NullMarked
package de.mhus.vance.shared.prak;

import org.jspecify.annotations.NullMarked;
