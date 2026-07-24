/**
 * Typed model for the {@code kind: finance-tree} document — the on-disk
 * shape parsed/serialized by
 * {@link de.mhus.vance.addon.brain.finance.FinanceTreeCodec}. Pure data,
 * no Spring / no shared-module dependency; the recursive tree of
 * {@link de.mhus.vance.addon.brain.finance.model.FinanceNode}s carries the
 * raw input (value records, sign, interest) — all derived/computed values
 * live under {@code $computed} and are produced by the math service, never
 * stored here as source of truth.
 */
@NullMarked
package de.mhus.vance.addon.brain.finance.model;

import org.jspecify.annotations.NullMarked;
