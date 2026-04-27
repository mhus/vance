package de.mhus.vance.brain.inbox;

import de.mhus.vance.api.inbox.InboxPendingSummaryData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.inbox.InboxItemService.PendingSummary;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Sends the {@link MessageType#INBOX_PENDING_SUMMARY} notification
 * once at session welcome / resume time, so the client knows
 * immediately whether there are open items waiting — without an
 * extra round-trip.
 *
 * <p>Skipped silently when the user has nothing pending — no point
 * pinging "0 items", and the client-side counter starts at zero
 * anyway.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InboxPendingSummaryPusher {

    private final InboxItemService inboxItemService;
    private final WebSocketSender sender;

    /**
     * Push a summary frame to {@code wsSession} for the given user.
     * Call after {@code SessionConnectionRegistry.register} but
     * before any other reply — the client should see this first.
     */
    public void pushIfAny(WebSocketSession wsSession, String tenantId, String userId) {
        PendingSummary summary = inboxItemService.summarizePendingForUser(tenantId, userId);
        if (summary.totalPending() <= 0) {
            return;
        }
        InboxPendingSummaryData data = InboxPendingSummaryData.builder()
                .totalPending(summary.totalPending())
                .byCriticality(summary.byCriticality())
                .oldestPendingAt(summary.oldestPendingAt())
                .build();
        try {
            sender.sendNotification(wsSession, MessageType.INBOX_PENDING_SUMMARY, data);
        } catch (IOException ioe) {
            log.warn("Failed to push inbox-pending-summary to user='{}': {}",
                    userId, ioe.toString());
        }
    }
}
