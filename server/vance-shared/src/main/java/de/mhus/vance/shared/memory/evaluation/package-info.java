/**
 * Memory-evaluation pipeline types.
 *
 * <p>Shared input/output data structures for the LLM-driven memory
 * analyzer described in {@code planning/memory-evaluation-pipeline.md}.
 * Single analyzer pass produces a list of {@link
 * de.mhus.vance.shared.memory.evaluation.ExtractedItem}s; downstream
 * consumers (span-strength, memory promotion, conflict surfacing) read
 * the parts relevant to them.
 *
 * <p>Records here are pure data — no service logic, no Mongo mapping.
 * Persistence happens through the existing {@code MemoryDocument} /
 * {@code ChatMessageDocument} channels; this package is the on-the-
 * wire shape between analyzer and consumer.
 */
@NullMarked
package de.mhus.vance.shared.memory.evaluation;

import org.jspecify.annotations.NullMarked;
