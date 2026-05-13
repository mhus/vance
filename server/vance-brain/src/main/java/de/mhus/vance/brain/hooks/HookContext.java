package de.mhus.vance.brain.hooks;

import de.mhus.vance.api.hooks.HookEventName;
import java.time.Instant;

/**
 * Per-run scope passed to a {@link HookRunner}. Carries the
 * identifying context the host-API exposes as {@code context.*}, plus
 * the correlation id that links every event-log row of one run.
 */
public record HookContext(
        String tenantId,
        String projectId,
        HookEventName event,
        String hookName,
        String correlationId,
        Instant firedAt) {
}
