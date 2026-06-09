/**
 * Fook — bug- and feature-ticket triage subsystem. See
 * {@code planning/fook-service.md}.
 *
 * <p>Three cooperating pieces:
 *
 * <ul>
 *   <li>{@link de.mhus.vance.brain.fook.FookTicketService} —
 *       internal CRUD + similarity search over
 *       {@code fook-ticket} documents in the
 *       {@value de.mhus.vance.shared.tenant.TenantService#SYSTEM_TENANT}
 *       tenant. NOT exposed as LLM tools — write paths run from
 *       {@code FookService} after the triage LLM call.</li>
 *   <li>{@code FookService} (forthcoming) — in-memory per-pod
 *       queue, worker thread, calls
 *       {@link de.mhus.vance.brain.ai.light.LightLlmService} with
 *       the {@code fook} recipe, applies the triage decision as
 *       side-effects, writes an inbox item for the reporter.</li>
 *   <li>Recipe {@code _vance/recipes/fook.yaml} —
 *       {@code engine: jeltz}, {@code internal: true}. Pebble
 *       prompt renders the submission + pre-loaded candidates and
 *       asks the LLM for one of {@code new_ticket} /
 *       {@code merge_into} / {@code discard}.</li>
 * </ul>
 */
@NullMarked
package de.mhus.vance.brain.fook;

import org.jspecify.annotations.NullMarked;
