package de.mhus.vance.brain.prak;

import org.jspecify.annotations.Nullable;

/**
 * Scope under which {@link PrakPromotionService} persists items.
 *
 * <p>{@code tenantId} and {@code projectId} together are the default
 * anchor; the per-item {@link de.mhus.vance.shared.prak.Scope} can
 * narrow further (to session or process) or widen to global. The
 * service uses the caller's defaults whenever an item-scope ID is
 * absent.
 *
 * <p>{@code runId} is a short correlation key emitted into the
 * persisted memory metadata so audit can match a memory entry back to
 * the analyzer run that produced it.
 */
public record PromotionContext(
        String tenantId,
        String projectId,
        @Nullable String sessionId,
        @Nullable String processId,
        String runId) {
}
