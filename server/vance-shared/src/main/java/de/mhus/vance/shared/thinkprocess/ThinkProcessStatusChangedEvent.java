package de.mhus.vance.shared.thinkprocess;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import org.jspecify.annotations.Nullable;

/**
 * Spring application-event published by {@link ThinkProcessService}
 * after a successful status transition. Listeners (e.g. the
 * brain-side parent-notification dispatcher) react to status changes
 * without {@code vance-shared} needing to know about the brain.
 *
 * <p>{@link #priorStatus} is {@code null} only when the prior state
 * couldn't be read (in practice: row already gone) — in that case
 * {@code newStatus} is also indeterminate and listeners should bail.
 */
public record ThinkProcessStatusChangedEvent(
        String processId,
        String tenantId,
        String sessionId,
        @Nullable String parentProcessId,
        @Nullable ThinkProcessStatus priorStatus,
        ThinkProcessStatus newStatus) {
}
