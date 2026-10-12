/**
 * Wire-contract DTOs for Zem — the System-One decision engine. A
 * decision model answers typed questions about a state with calibrated
 * probabilities instead of text: {@code noul} (yes/no), {@code choice}
 * (exactly one of N options) and {@code score} (position on an ordered
 * scale).
 *
 * <p>The shapes follow TypeSafe's System-One API — the de-facto standard
 * that OpenRouter, Ollama, Upstage and others speak — so provider
 * adapters translate as little as possible. See
 * {@code planning/zem-engine.md} §4.
 */
@NullMarked
package de.mhus.vance.api.zem;

import org.jspecify.annotations.NullMarked;
