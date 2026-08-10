package de.mhus.vance.brain.progress;

import de.mhus.vance.api.thinkprocess.ProcessCountsNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.events.SessionRosterChangedEvent;
import de.mhus.vance.brain.session.SessionChatBootstrapper;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService.ProcessCounts;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Pushes the {@link MessageType#PROCESS_COUNTS} frame so clients can render
 * a status-bar badge ("2 running, 1 blocked") without polling
 * {@code process-list}.
 *
 * <p>Two entry points, mirroring the inbox pattern
 * ({@code InboxPendingSummaryPusher} + {@code WsNotificationChannel}):
 *
 * <ul>
 *   <li>{@link #pushInitial} — once per welcome / resume, to the one
 *       connection that just bound, so the badge is correct without a
 *       round-trip.
 *   <li>{@link #onStatusChanged} — deltas afterwards, broadcast to every
 *       connection of the session.
 * </ul>
 *
 * <p><b>Coalescing is the point.</b> {@code RUNNING ↔ IDLE} flips on every
 * turn of every process — that is exactly why
 * {@link EngineLifecycleProgressListener} drops those transitions from the
 * progress channel. A counter needs them, but only when they move the
 * <em>numbers</em>: we keep the last frame sent per session and stay silent
 * when the recomputed counts are equal. Without that filter this would emit
 * one frame per LLM turn per worker.
 *
 * <p>The session's own chat-process is excluded (it is always there and
 * would pin the badge at "1"); processes living in system sessions
 * (Agrajag, trigger spawns) never reach a user connection because the frame
 * is addressed by {@code sessionId}.
 *
 * <p>See {@code planning/process-visibility.md} §4.A.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessCountsPusher {

    private final ThinkProcessService thinkProcessService;
    private final ClientEventPublisher events;
    private final SessionConnectionRegistry connections;
    private final WebSocketSender sender;

    /**
     * Last counts published per session — the coalescing filter.
     *
     * <p>Two eviction paths, because one alone leaks: a failed publish drops
     * the entry (nobody listening), and {@link #onRosterChanged} drops it
     * when the session's last connection goes away. Without the second path
     * a chat-only session — one {@code pushInitial}, never another status
     * transition — would keep its entry for the pod's lifetime.
     */
    private final Map<String, ProcessCounts> lastSent = new ConcurrentHashMap<>();

    /**
     * Send the current counts to a single freshly-bound connection.
     * Unconditional — unlike the inbox summary we also push zeros, because
     * a reconnecting client may carry a stale non-zero badge from its
     * previous connection.
     */
    public void pushInitial(WebSocketSession wsSession, String tenantId, String sessionId) {
        ProcessCounts counts = countFor(tenantId, sessionId);
        try {
            sender.sendNotification(wsSession, MessageType.PROCESS_COUNTS,
                    toNotification(sessionId, counts));
            lastSent.put(sessionId, counts);
        } catch (IOException ioe) {
            log.debug("Failed to push process-counts to session='{}': {}",
                    sessionId, ioe.toString());
        }
    }

    /**
     * Recompute on every status transition and broadcast when the numbers
     * moved. Cheap by design: one indexed session query, then an in-memory
     * fold and an equality check.
     */
    @EventListener
    public void onStatusChanged(ThinkProcessStatusChangedEvent event) {
        if (event.priorStatus() == event.newStatus()) {
            return;
        }
        String sessionId = event.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        ProcessCounts counts = countFor(event.tenantId(), sessionId);
        // compute-and-compare under the map's per-key lock so two lanes
        // transitioning concurrently cannot both decide "changed" and emit
        // the same frame twice.
        boolean[] changed = new boolean[1];
        lastSent.compute(sessionId, (key, previous) -> {
            if (counts.equals(previous)) {
                return previous;
            }
            changed[0] = true;
            return counts;
        });
        if (!changed[0]) {
            return;
        }
        boolean delivered = events.publish(sessionId, MessageType.PROCESS_COUNTS,
                toNotification(sessionId, counts));
        if (!delivered) {
            // Nobody listening — forget the session so a later reconnect
            // gets a fresh frame instead of being coalesced away against
            // state no client ever saw.
            lastSent.remove(sessionId);
        }
    }

    /**
     * Forget a session once nothing is connected to it any more. The registry
     * fires this after every roster mutation, so an unbind / disconnect /
     * session close all land here.
     */
    @EventListener
    public void onRosterChanged(SessionRosterChangedEvent event) {
        String sessionId = event.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (connections.findAll(sessionId).isEmpty()) {
            lastSent.remove(sessionId);
        }
    }

    private ProcessCounts countFor(String tenantId, String sessionId) {
        return thinkProcessService.countBySession(
                tenantId, sessionId, SessionChatBootstrapper.CHAT_PROCESS_NAME);
    }

    private static ProcessCountsNotification toNotification(
            String sessionId, ProcessCounts counts) {
        return ProcessCountsNotification.builder()
                .sessionId(sessionId)
                .running(counts.running())
                .waiting(counts.waiting())
                .blocked(counts.blocked())
                .total(counts.total())
                .build();
    }
}
