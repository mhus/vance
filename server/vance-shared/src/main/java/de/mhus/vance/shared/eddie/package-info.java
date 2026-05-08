/**
 * Server-side Eddie-engine state.
 *
 * <p>Holds embedded Mongo types Eddie persists on her own
 * {@code ThinkProcessDocument} — currently {@link WorkerLinkSnapshot},
 * the per-worker mirror that carries Channel-Mode, last-seen plan
 * snapshot, and triage working-memory. See
 * {@code specification/eddie-engine.md} §7 + §8 and the planning docs
 * {@code planning/eddie-moderator-erweiterung.md} (triage) and
 * {@code planning/eddie-plan-mode.md} (plan-mirror + fusion).
 */
@NullMarked
package de.mhus.vance.shared.eddie;

import org.jspecify.annotations.NullMarked;
