package de.mhus.vance.brain.notification;

import de.mhus.vance.api.notification.NotificationDto;
import de.mhus.vance.api.notification.NotificationSeverity;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Single emit path for the user-notification side-channel. Tools, the
 * Hactar script bridge, and any internal Java path funnel through here;
 * the source block ({@code sessionId}, {@code sourceProcessId}, …) is
 * derived from the supplied {@link ThinkProcessDocument} so callers
 * cannot accidentally ship a notification without it.
 *
 * <p>Routing is session-bound: the notification is published on the
 * process's owning session id and reaches every WS connection currently
 * bound to that session. No active client → notification is dropped on
 * the floor (intentional — see
 * {@code specification/user-notification-channel.md} §4).
 *
 * <p>Unlike {@code ProgressEmitter}, this service has no verbosity
 * filter: a notification is always explicit, never optional engine
 * chatter.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final ClientEventPublisher events;

    /**
     * Publish a notification for the given think-process.
     *
     * @param process  origin process — its sessionId is the routing key
     * @param text     short attention text (≤120 chars recommended)
     * @param severity {@code null} → defaults to {@link NotificationSeverity#INFO}
     * @return true if the frame reached at least one connection
     */
    public boolean publish(
            ThinkProcessDocument process,
            String text,
            @Nullable NotificationSeverity severity) {
        if (process == null) {
            throw new IllegalArgumentException("process must not be null");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        String sessionId = process.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            // No session anchor — nowhere to route. Log and drop.
            log.debug("notify: dropping notification — process '{}' has no sessionId",
                    process.getId());
            return false;
        }
        NotificationDto dto = NotificationDto.builder()
                .text(text)
                .severity(severity == null ? NotificationSeverity.INFO : severity)
                .emittedAt(Instant.now())
                .sourceProcessId(process.getId())
                .sourceProcessName(process.getName())
                .sourceProcessTitle(process.getTitle())
                .sessionId(sessionId)
                .build();
        return events.publish(sessionId, MessageType.NOTIFY, dto);
    }
}
