/**
 * Brain-side stages of the memory-evaluation pipeline.
 *
 * <p>This package holds the deterministic Java-side components that
 * wrap the LLM analyzer: the hot-path marker detector and cheap-path
 * pre-filter that decide <em>whether</em> to call the analyzer,
 * and the sanitizer that validates and corrects what comes back.
 *
 * <p>The analyzer itself, the strength-deriver, the promotion service
 * and trigger orchestration are added in later phases — see
 * {@code planning/memory-evaluation-pipeline.md} §12 for the staged
 * implementation order.
 */
@NullMarked
package de.mhus.vance.brain.memory.evaluation;

import org.jspecify.annotations.NullMarked;
