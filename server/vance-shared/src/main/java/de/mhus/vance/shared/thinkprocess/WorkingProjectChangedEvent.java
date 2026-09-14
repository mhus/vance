package de.mhus.vance.shared.thinkprocess;

import org.jspecify.annotations.Nullable;

/**
 * Spring application-event published by {@link ThinkProcessService} after
 * the working-project pointer ("spot") of a think-process actually moved.
 * Listeners (e.g. the brain-side working-project pusher) broadcast the new
 * focus to the session's clients without {@code vance-shared} needing to
 * know about the WebSocket layer.
 *
 * <p>Published from the single write path
 * {@link ThinkProcessService#setWorkingProjectId} — every mutation site
 * (the LLM {@code project_switch} tool, the WS {@code project-switch}
 * request, Eddie's DELEGATE side-effect) funnels through it, so listeners
 * see every switch regardless of origin. A re-set of the identical value
 * is a no-op and does not fire.
 */
public record WorkingProjectChangedEvent(
        String processId,
        String tenantId,
        String sessionId,
        @Nullable String workingProjectId) {}
