package de.mhus.vance.shared.thinkprocess;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.thinkprocess.ActiveAppContext;
import de.mhus.vance.api.thinkprocess.BoundDocSelection;
import de.mhus.vance.api.thinkprocess.PeerEventType;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ToolCallStatus;
import java.time.Instant;
import java.util.List;
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

    /**
     * Display name of the sending user captured at the receive
     * handler — see {@code planning/multi-user-sessions.md} §3.5 /
     * §5. Round-tripped through the persistent queue so a pod-restart
     * replay preserves the multi-user attribution. {@code null} for
     * non-USER_CHAT_INPUT entries and for legacy rows.
     */
    private @Nullable String fromUserDisplayName;

    /** Free-form text payload — used by {@code USER_CHAT_INPUT} and as
     *  the human-readable summary of {@code PROCESS_EVENT}. */
    private @Nullable String content;

    /**
     * Document ids that should ride with this user chat input as
     * multimodal attachments (image / PDF / text). Only the ids are
     * persisted; the brain re-resolves and re-validates against the
     * project scope every time the engine drains the queue. {@code null}
     * or empty when the turn is text-only.
     */
    private @Nullable List<String> attachmentDocumentIds;

    /**
     * Per-message voice-mode flag — {@code true} when the originating
     * client expected a TTS-friendly reply for this single turn
     * (speaker / talk-mode active). The engine reaches this through
     * to the Pebble render context as {@code voiceMode}; engines
     * render a voice-block in their system prompt accordingly.
     *
     * <p>Per-message, not session state. {@code null} or absent on
     * pre-voice-mode rows and non-USER_CHAT_INPUT entries — the
     * Codec coerces null to {@code false}. Wrapper type instead of
     * primitive so Spring Data MongoDB persists the field even when
     * the value is {@code false} (primitive defaults are otherwise
     * skipped by some mapping paths).
     * See {@code specification/voice-mode.md}.
     */
    private @Nullable Boolean voiceMode;

    /**
     * Per-message hint that the user was viewing a folder-level app
     * (Calendar / Kanban / Slideshow …) when this chat turn was sent.
     * Engine drain picks the most recent value off the queue and feeds
     * the prompt template the Pebble variable {@code activeApp} plus
     * a dynamic {@code appInstructions} markdown chunk returned by the
     * app's {@code VanceApplication.promptInject(...)}.
     *
     * <p>Per-message, not session state. {@code null} on non-USER_CHAT_INPUT
     * rows and on legacy rows from before this field existed.
     * See {@code planning/apps-in-cortex-and-live.md} §5.
     */
    private @Nullable ActiveAppContext activeApp;

    /**
     * Per-message id of the document the user has "bound" to the Cortex
     * chat for this turn. The engine drain picks the most recent value
     * off the queue, resolves the document, and inlines its current
     * content into the prompt render — per-turn only, never written back
     * into chat history. {@code null} on non-USER_CHAT_INPUT rows and
     * when no file is bound.
     */
    private @Nullable String boundDocumentId;

    /**
     * Character range selected in the bound document at send time
     * (from..to offsets). Per-message; surfaced to the prompt so the
     * model knows a selection exists and can read it via
     * {@code doc_get_selection}. {@code null} when nothing is selected.
     */
    private @Nullable BoundDocSelection boundDocSelection;

    // ─── PROCESS_EVENT ───────────────────────────────────────────
    /** {@code ThinkProcessDocument.id} of the emitter. */
    private @Nullable String sourceProcessId;

    private @Nullable ProcessEventType eventType;

    /**
     * Stable handle for this PROCESS_EVENT — UUID assigned when the
     * event is queued by {@code ProcessEventEmitter}. Parent engines
     * reference it in follow-up actions (Arthur's {@code RELAY
     * eventRef}), so the persistence round-trip must preserve it.
     * {@code null} only on rows written before the field existed.
     */
    private @Nullable String eventId;

    /**
     * Timestamp of the user-input turn the emitting worker was
     * responding to when it produced this event — set by
     * {@code ParentNotificationListener} from the worker's chat
     * history. Lets a parent engine distinguish a fresh reply from a
     * stale one (the Marvin-spawn / Ford-stale-relay bug pattern).
     * {@code null} for engine-driven SUMMARY events or when the
     * triggering input could not be identified.
     */
    private @Nullable Instant inResponseToAt;

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
    private @Nullable String sourceEddieProcessId;

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
