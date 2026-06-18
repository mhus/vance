/**
 * Eddie's Output-Triage stage: decides per worker frame whether Eddie
 * passes it through verbatim, reformulates it, or routes it to the
 * user's inbox. Companion to the per-worker
 * {@link de.mhus.vance.shared.thinkprocess.WorkerLinkSnapshot} mirror — the
 * triage result is the source of {@code triageSummary} /
 * {@code lastCriticality} on the snapshot.
 *
 * <p>See {@code planning/eddie-moderator-erweiterung.md}.
 */
@NullMarked
package de.mhus.vance.brain.eddie.triage;

import org.jspecify.annotations.NullMarked;
