package de.mhus.vance.brain.notifications;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.api.session.SessionStatus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Pushes {@link MessageType#INBOX_ITEM_ADDED}-style notifications
 * to all active WS connections of the recipient user.
 *
 * <p>Activation is implicit — if any session of the user has an
 * open WS connection in this brain pod, the channel delivers. No
 * per-channel enable-toggle in settings; the connection presence
 * IS the signal (per spec §12.2).
 *
 * <p>Multi-device: a user with both Desktop and Mobile clients
 * connected gets pushed to all of them. CRITICAL items thus reach
 * every device in parallel.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WsNotificationChannel implements NotificationChannel {

    private final SessionService sessionService;
    private final SessionConnectionRegistry connectionRegistry;
    private final WebSocketSender sender;

    @Override
    public String name() {
        return "ws";
    }

    @Override
    public boolean canHandle(NotifyEvent event) {
        return !findActiveWsSessions(event).isEmpty();
    }

    @Override
    public DeliveryResult deliver(NotifyEvent event) {
        List<WebSocketSession> targets = findActiveWsSessions(event);
        if (targets.isEmpty()) {
            return DeliveryResult.skipped("no active WS for user");
        }
        int sent = 0;
        int failed = 0;
        InboxAddedNotificationData data = new InboxAddedNotificationData(
                event.inboxItemId(), event.criticality().name(),
                event.title(), event.body());
        for (WebSocketSession ws : targets) {
            try {
                sender.sendNotification(ws, MessageType.INBOX_ITEM_ADDED, data);
                sent++;
            } catch (IOException ioe) {
                failed++;
                log.warn("WsNotificationChannel: failed pushing to ws='{}': {}",
                        ws.getId(), ioe.toString());
            }
        }
        if (sent == 0) {
            return DeliveryResult.failed(
                    "all " + failed + " WS write(s) failed");
        }
        if (failed > 0) {
            return new DeliveryResult(DeliveryResult.Status.SENT,
                    "partial: " + sent + " sent / " + failed + " failed");
        }
        return DeliveryResult.sent();
    }

    private List<WebSocketSession> findActiveWsSessions(NotifyEvent event) {
        List<SessionDocument> sessions = sessionService.listForUser(
                event.tenantId(), event.userId());
        List<WebSocketSession> out = new ArrayList<>();
        for (SessionDocument s : sessions) {
            if (s.getStatus() == SessionStatus.CLOSED) continue;
            connectionRegistry.find(s.getSessionId()).ifPresent(out::add);
        }
        return out;
    }

    /** Wire payload for {@link MessageType#INBOX_ITEM_ADDED}. */
    private record InboxAddedNotificationData(
            String itemId,
            String criticality,
            String title,
            String body) {}
}
