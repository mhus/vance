/**
 * Fenchurch image-generation service plus the four supporting tools
 * (image_generate, image_style_set, image_style_get, image_style_prompt).
 * Pattern follows {@code DiscoveryService} / {@code FookService}: a
 * Spring {@code @Service} that orchestrates a single LLM/provider
 * call, no Worker-Engine, no ThinkProcess lifecycle.
 *
 * <p>Sub-systems:
 * <ul>
 *   <li>{@link FenchurchStyleService} — concatenative style-prefix
 *       cascade (tenant → user → project → session)</li>
 *   <li>{@link ImageCallTracker} — per-image call counter +
 *       quota check against {@code ai.fenchurch.daily_images} /
 *       {@code monthly_images}</li>
 *   <li>{@link FenchurchService} — generation entry point: style
 *       merge, title generation via LightLlm, provider dispatch,
 *       heartbeat ticker, cancel path</li>
 * </ul>
 *
 * <p>See {@code planning/fenchurch-service.md} for the design.
 */
@NullMarked
package de.mhus.vance.brain.fenchurch;

import org.jspecify.annotations.NullMarked;
