package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One entry of a think-process's pending message queue — the client-side
 * mirror of a queued but not yet drained steer message ("active message
 * queue", see {@code planning/active-message-queue.md} §4 P3).
 *
 * <p>Clients render these as the "queued" list of the chat composer: the
 * message is durably persisted (the {@code process-steer} ack is
 * persist-bound) but the engine has not picked it up yet. The
 * {@code process-queue} notification flips entries from queued to picked
 * up via {@code drainedIds}.
 *
 * <p>Only the display-relevant fields ride here — the full steering
 * context (attachments, active app, bound selection) stays server-side
 * and is delivered to the engine on drain.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class QueuedMessageEntry {

    /** Idempotent message id — also the key used by {@code drainedIds}. */
    private @Nullable String messageId;

    /** Entry kind name ({@code PendingMessageType}, e.g. {@code USER_CHAT_INPUT}). */
    private @Nullable String type;

    /** When the message was enqueued. */
    private @Nullable Instant createdAt;

    /** User id of the sender — {@code null} for engine-produced entries. */
    private @Nullable String senderUserId;

    /** Display name of the sender. */
    private @Nullable String senderDisplayName;

    /** Message text (may be blank for non-user entry kinds). */
    private @Nullable String content;
}
