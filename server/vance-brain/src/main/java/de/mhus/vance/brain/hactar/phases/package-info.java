/**
 * Hactar phase components — one Spring bean per lifecycle phase
 * (FRAMING / REVIEWING / DRAFTING / VALIDATING / EXECUTING). The
 * engine dispatches over status and delegates to the matching phase;
 * each phase mutates the {@code HactarState} and returns the
 * next status. Shared prompt-enrichment (tool/manual/skill inventories)
 * lives in {@link HactarContextRenderer}.
 *
 * <p>Mirrors the structure of the Slartibartfast {@code phases}
 * package — see {@code planning/hactar-engine.md}.
 */
@NullMarked
package de.mhus.vance.brain.hactar.phases;

import org.jspecify.annotations.NullMarked;
