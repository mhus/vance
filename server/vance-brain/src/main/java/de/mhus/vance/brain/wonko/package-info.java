/**
 * Wonko — the engine that asks what everything is for.
 *
 * <p>Checks a subject (documents in v1) for sense, contradictions and gaps
 * and clarifies it with the user over repeated rounds: check → decide what
 * the mandate allows → ask a question set → incorporate the answers → check
 * again. The outer loop (rounds, register, mandate rules, convergence) is
 * code; the model is called only at two schema-bound edges — the check and
 * the interpretation of free-text answers.
 *
 * <p>What was clarified lives in a review register in project memory
 * ({@code MemoryKind.REVIEW}), so a later run on the same subject remembers
 * what the user called "good as it is". Plan: {@code planning/wonko-engine.md}.
 */
@NullMarked
package de.mhus.vance.brain.wonko;

import org.jspecify.annotations.NullMarked;
