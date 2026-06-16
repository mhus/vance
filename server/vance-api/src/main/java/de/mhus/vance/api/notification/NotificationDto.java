package de.mhus.vance.api.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload of the {@code notify} server message — a short, flüchtig
 * attention-ping that makes the client beep ("BEEP, fertig mit XXX").
 *
 * <p>Use cases: process completion, long-running batch finished, a
 * worker hit a wait, an escalation surfaces. Anything that needs the
 * user to <em>glance over</em> but doesn't require an answer — that's
 * what the inbox is for.
 *
 * <p>The source block ({@link #sourceProcessId}, {@link #sourceProcessName},
 * {@link #sourceProcessTitle}, {@link #sessionId}) is populated when the
 * notification was emitted by a think-process; the client uses it to
 * deep-link from a system notification click into the right chat session.
 * Notifications emitted outside of a process context (cluster / admin
 * paths) leave it blank.
 *
 * <p>Side-channel only — does <strong>not</strong> enter conversation
 * history, is not persisted server-side, and is not replayed on
 * reconnect. If the user has no live client when a notification fires,
 * it's gone. Use the inbox if persistence is required.
 *
 * <p>See {@code specification/user-notification-channel.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("notification")
public class NotificationDto {

    /** Short human-readable text — recommend ≤ 120 chars, no hard limit. */
    private String text;

    /** Severity for client-side rendering. Defaults to {@link NotificationSeverity#INFO} server-side. */
    private NotificationSeverity severity;

    /** When the brain emitted this notification. */
    private @Nullable Instant emittedAt;

    /** Process that triggered the notification, if any. */
    private @Nullable String sourceProcessId;

    /** Technical name of the source process (e.g. {@code "chat"}, {@code "worker-1"}). */
    private @Nullable String sourceProcessName;

    /** Display title of the source process, if set. */
    private @Nullable String sourceProcessTitle;

    /** Session the source process belongs to — used as deep-link target. */
    private @Nullable String sessionId;
}
