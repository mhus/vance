package de.mhus.vance.shared.enginemessage;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.thinkprocess.PeerEventType;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ToolCallStatus;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One engine-to-engine (or client-to-engine) message, with routing and
 * full lifecycle persisted as a top-level document.
 *
 * <p>The message moves through three lifecycle states, each tagged by a
 * timestamp on this document:
 *
 * <ol>
 *   <li><b>Outboxed</b> — sender inserted, {@link #deliveredAt} is
 *       {@code null}. The sender keeps replaying until ack arrives.</li>
 *   <li><b>Inboxed</b> — receiver persisted (delivery acknowledged),
 *       {@link #deliveredAt} set, {@link #drainedAt} still {@code null}.
 *       Sits in the receiver's inbox awaiting lane processing.</li>
 *   <li><b>Drained</b> — lane consumed, {@link #drainedAt} set. Subject
 *       to TTL cleanup after the configured retention window.</li>
 * </ol>
 *
 * <p>Reuses {@link PendingMessageType} as the type discriminator; the
 * variant-specific payload fields are flattened on this document
 * (rather than nested) to keep Mongo round-trips trivial and indexes
 * straightforward — same pattern as
 * {@link de.mhus.vance.shared.thinkprocess.PendingMessageDocument}.
 *
 * <p>{@link #at} from the legacy {@code PendingMessageDocument} is
 * subsumed by {@link #createdAt}; the legacy {@code idempotencyKey} is
 * subsumed by {@link #messageId} (idempotency-key by construction).
 *
 * <p>TTL: only {@link #drainedAt} is indexed for expiry. Documents
 * with {@code drainedAt == null} (outboxed or inboxed-not-drained) are
 * never auto-removed, so a sender's pending replay queue and a
 * receiver's pending inbox both survive arbitrarily long.
 *
 * <p>See {@code specification/engine-message-routing.md} §3.
 */
@Document(collection = "engine_messages")
@CompoundIndexes({
        @CompoundIndex(
                name = "outbox_replay",
                def = "{ 'senderProcessId': 1, 'deliveredAt': 1 }"),
        @CompoundIndex(
                name = "inbox_drain",
                def = "{ 'targetProcessId': 1, 'drainedAt': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EngineMessageDocument {

    /**
     * Sender-assigned message identifier. Doubles as idempotency key —
     * a duplicate insert with the same {@code messageId} is the
     * receiver's signal that the sender is replaying.
     */
    @Id
    private String messageId = "";

    private String tenantId = "";

    /** {@code ThinkProcessDocument.id} of the producer. */
    private String senderProcessId = "";

    /** {@code ThinkProcessDocument.id} of the consumer. */
    private String targetProcessId = "";

    /** Set on insert by the sender. */
    private Instant createdAt = Instant.EPOCH;

    /**
     * Set when the receiver durably accepts the message into its inbox
     * (ack-on-persist). {@code null} while the message is still in the
     * sender's outbox.
     */
    private @Nullable Instant deliveredAt;

    /**
     * Set when the receiver's lane has consumed the message and turned
     * it into engine state. Drives the TTL reaper.
     */
    @Indexed(expireAfterSeconds = 86_400)
    private @Nullable Instant drainedAt;

    private PendingMessageType type = PendingMessageType.USER_CHAT_INPUT;

    // ─── USER_CHAT_INPUT ─────────────────────────────────────────
    private @Nullable String fromUser;
    private @Nullable String content;

    // ─── PROCESS_EVENT ───────────────────────────────────────────
    /**
     * {@code ThinkProcessDocument.id} of the emitter for
     * {@code PROCESS_EVENT}. Distinct from {@link #senderProcessId}:
     * for events relayed via a parent-notification listener the
     * sender may be a system component while the source is the
     * worker process whose state changed.
     */
    private @Nullable String sourceProcessId;

    private @Nullable ProcessEventType eventType;

    // ─── TOOL_RESULT ─────────────────────────────────────────────
    private @Nullable String toolCallId;
    private @Nullable String toolName;
    private @Nullable ToolCallStatus toolStatus;
    private @Nullable String error;

    // ─── EXTERNAL_COMMAND ────────────────────────────────────────
    private @Nullable String command;

    // ─── INBOX_ANSWER ────────────────────────────────────────────
    private @Nullable String inboxItemId;
    private @Nullable InboxItemType inboxItemType;
    private @Nullable AnswerPayload inboxAnswer;

    // ─── PEER_EVENT ──────────────────────────────────────────────
    private @Nullable String sourceEddieProcessId;
    private @Nullable String peerUserId;
    private @Nullable PeerEventType peerEventType;

    /** Generic structured payload — see {@link PendingMessageType} variants. */
    private @Nullable Map<String, Object> payload;
}
