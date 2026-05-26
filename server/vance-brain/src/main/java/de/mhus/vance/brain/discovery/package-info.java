/**
 * Discovery — capability-search subsystem behind the
 * {@code how_do_i} tool. See {@code specification/how-do-i.md} for the
 * design, {@code specification/light-llm-service.md} for the LLM-call
 * helper this service builds on.
 *
 * <p>The {@code SourceCatalogService} renders all engine manuals,
 * skill definitions and tool descriptions for a tenant into a single
 * Markdown block ("catalog") that the {@code DiscoveryService} hands
 * to the {@code how-do-i} recipe via the Pebble variable
 * {@code {{ sources }}}. The LLM then picks the matching capability
 * and returns it as a structured JSON reply.
 */
@NullMarked
package de.mhus.vance.brain.discovery;

import org.jspecify.annotations.NullMarked;
