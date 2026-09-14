package de.mhus.vance.brain.progress;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WorkingProjectNotification;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.session.SessionChatBootstrapper;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.WorkingProjectChangedEvent;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Pushes the {@link MessageType#WORKING_PROJECT_CHANGED} frame so clients
 * can show which project the hub's chat-process currently coordinates —
 * Eddie's "spot" — without polling {@code project-switch} or reading the
 * process list.
 *
 * <p>Two entry points, mirroring {@link ProcessCountsPusher}:
 *
 * <ul>
 *   <li>{@link #pushInitial} — once per welcome / resume / bootstrap, to
 *       the one connection that just bound. Also pushes {@code null}, so
 *       a reconnecting client drops a stale badge from a previous
 *       connection.
 *   <li>{@link #onWorkingProjectChanged} — every actual spot mutation,
 *       broadcast to every connection of the session. The event comes
 *       from {@code ThinkProcessService.setWorkingProjectId}, the single
 *       write path, so the LLM {@code project_switch} tool, the WS
 *       {@code project-switch} request and Eddie's DELEGATE side-effect
 *       all reach the client without each call-site knowing about the
 *       WebSocket layer.
 * </ul>
 *
 * <p>No coalescing needed: spot moves are rare and user-visible, and the
 * service already swallows no-op re-sets (same value → no event). Pod-local
 * like the counts push — cross-pod clients get the current spot via the
 * initial push on their next bind.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkingProjectPusher {

    private final ThinkProcessService thinkProcessService;
    private final ClientEventPublisher events;
    private final WebSocketSender sender;

    /**
     * Send the current spot of the session's chat process to a single
     * freshly-bound connection. Unconditional — a session without a spot
     * (freshly created, non-Eddie engines) sends {@code workingProject:
     * null}, which is exactly the state a reconnecting client should
     * render.
     */
    public void pushInitial(WebSocketSession wsSession, String tenantId, String sessionId) {
        String spot = thinkProcessService
                .findByName(tenantId, sessionId, SessionChatBootstrapper.CHAT_PROCESS_NAME)
                .map(p -> p.getWorkingProjectId())
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);
        try {
            sender.sendNotification(
                    wsSession,
                    MessageType.WORKING_PROJECT_CHANGED,
                    WorkingProjectNotification.builder()
                            .sessionId(sessionId)
                            .workingProject(spot)
                            .build());
        } catch (IOException ioe) {
            log.debug("Failed to push working-project to session='{}': {}", sessionId, ioe.toString());
        }
    }

    /** Broadcast a spot mutation to every connection of the session. */
    @EventListener
    public void onWorkingProjectChanged(WorkingProjectChangedEvent event) {
        String sessionId = event.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        events.publish(
                sessionId,
                MessageType.WORKING_PROJECT_CHANGED,
                WorkingProjectNotification.builder()
                        .sessionId(sessionId)
                        .workingProject(event.workingProjectId())
                        .build());
    }
}
