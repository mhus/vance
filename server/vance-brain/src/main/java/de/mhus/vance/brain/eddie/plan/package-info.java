/**
 * Eddie's plan-mirror + fusion machinery.
 *
 * <p>Eddie observes worker plan-frames ({@code todos-updated},
 * {@code plan-proposed}, {@code process-mode-changed}) over the
 * Working-WS, mirrors the latest snapshot onto each worker's
 * {@link de.mhus.vance.shared.eddie.WorkerLinkSnapshot}, and fuses
 * the per-worker mirrors with her own todos into one
 * {@link de.mhus.vance.api.thinkprocess.TodosUpdatedNotification} so
 * the user-client sees a single TodoList — with stable source
 * prefixes on each item id.
 *
 * <p>See {@code planning/eddie-plan-mode.md} §2.
 */
@NullMarked
package de.mhus.vance.brain.eddie.plan;

import org.jspecify.annotations.NullMarked;
