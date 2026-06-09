/**
 * Fook upstream-transport subsystem — moves locally triaged
 * tickets out to the configured external ticket system
 * (default: GitHub Issues) and mirrors status / comments back.
 *
 * <p>See {@code specification/fook-upstream.md}. Core pieces:
 *
 * <ul>
 *   <li>{@link de.mhus.vance.brain.fook.upstream.TicketProvider} —
 *       provider-agnostic interface; v1 has one implementation,
 *       {@code GitHubTicketProvider}.</li>
 *   <li>{@code FookTicketAnonymizer} — pure-logic scrubber that
 *       hashes reporter identity and runs regex redactions on
 *       free-form text before it leaves the tenant boundary.</li>
 *   <li>{@code FookUpstreamService} — two scheduled ticks: sender
 *       (pending → transferred via provider.create) and poll
 *       (mirror upstream state + comments).</li>
 * </ul>
 */
@NullMarked
package de.mhus.vance.brain.fook.upstream;

import org.jspecify.annotations.NullMarked;
