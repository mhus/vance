/**
 * Zaphod — the multi-head engine. Drives N heads (one Ford-style
 * sub-process each) sequentially against the same goal, then runs a
 * direct synthesizer LLM-call to produce one combined answer.
 * See {@code specification/zaphod-engine.md}.
 */
@NullMarked
package de.mhus.vance.brain.zaphod;

import org.jspecify.annotations.NullMarked;
