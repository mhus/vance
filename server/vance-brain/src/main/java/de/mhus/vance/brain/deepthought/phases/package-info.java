/**
 * Deep Thought phase components — one Spring bean per lifecycle phase
 * (FRAMING / REVIEWING / DRAFTING / VALIDATING / EXECUTING). The
 * engine dispatches over status and delegates to the matching phase;
 * each phase mutates the {@code DeepThoughtState} and returns the
 * next status. Shared prompt-enrichment (tool/manual/skill inventories)
 * lives in {@link DeepThoughtContextRenderer}.
 *
 * <p>Mirrors the structure of the Slartibartfast {@code phases}
 * package — see {@code planning/deepthought-engine.md}.
 */
@NullMarked
package de.mhus.vance.brain.deepthought.phases;

import org.jspecify.annotations.NullMarked;
