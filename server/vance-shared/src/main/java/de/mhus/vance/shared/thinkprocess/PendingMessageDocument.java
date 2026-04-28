package de.mhus.vance.shared.thinkprocess;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.thinkprocess.PeerEventType;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ToolCallStatus;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Persistent form of a queued steer message — embedded inside
 * {@link ThinkProcessDocument#getPendingMessages()} as a Mongo
 * sub-document.
 *
 * <p>This is a flat union: {@link #type} selects which subset of the
 * fields is meaningful. We store one POJO with optional fields rather
 * than a polymorphic hierarchy so Mongo round-trips stay trivial and
 * we avoid Spring Data's discriminator-class machinery. The engine
 * layer translates between this and the sealed {@code SteerMessage}
 * via {@code SteerMessageCodec}.
 *
 * <p>{@code vance-shared} cannot depend on {@code vance-brain}; that
 * is the reason for keeping the persistence form here and the
 * type-safe variant on the engine side.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingMessageDocument {

    private PendingMessageType type = PendingMessageType.USER_CHAT_INPUT;

    /** When the message was produced (not when it was dequeued). */
    private Instant at = Instant.EPOCH;

    /** Optional retry-safety key. */
    private @Nullable String idempotencyKey;

    // ─── USER_CHAT_INPUT ─────────────────────────────────────────
    /** {@code UserDocument.name} or system-pseudonym (e.g. {@code "process:<id>"}). */
    private @Nullable String fromUser;

    /** Free-form text payload — used by {@code USER_CHAT_INPUT} and as
     *  the human-readable summary of {@code PROCESS_EVENT}. */
    private @Nullable String content;

    // ─── PROCESS_EVENT ───────────────────────────────────────────
    /** {@code ThinkProcessDocument.id} of the emitter. */
    private @Nullable String sourceProcessId;

    private @Nullable ProcessEventType eventType;

    // ─── TOOL_RESULT ─────────────────────────────────────────────
    private @Nullable String toolCallId;

    private @Nullable String toolName;

    private @Nullable ToolCallStatus toolStatus;

    /** Set when {@code toolStatus == ERROR/TIMEOUT}. */
    private @Nullable String error;

    // ─── EXTERNAL_COMMAND ────────────────────────────────────────
    /** Command verb, e.g. {@code "approve"}, {@code "cancel"}. */
    private @Nullable String command;

    // ─── INBOX_ANSWER ────────────────────────────────────────────
    private @Nullable String inboxItemId;
    private @Nullable InboxItemType inboxItemType;
    private @Nullable AnswerPayload inboxAnswer;

    // ─── PEER_EVENT ──────────────────────────────────────────────
    /** {@code ThinkProcessDocument.id} of the emitting hub-process. */
    private @Nullable String sourceVanceProcessId;

    /** {@code UserDocument.name} — both peer hubs belong to the same user. */
    private @Nullable String peerUserId;

    private @Nullable PeerEventType peerEventType;

    // ─── Shared payload ──────────────────────────────────────────
    /**
     * Generic structured data.
     *
     * <ul>
     *   <li>{@code PROCESS_EVENT}: arbitrary key/value progress info.</li>
     *   <li>{@code TOOL_RESULT}: the tool's return value (when {@code SUCCESS}).</li>
     *   <li>{@code EXTERNAL_COMMAND}: command parameters.</li>
     *   <li>{@code PEER_EVENT}: structured side-channel data — e.g. project name,
     *       process id, status — alongside {@link #content} as a human summary.</li>
     * </ul>
     */
    private @Nullable Map<String, Object> payload;
}
