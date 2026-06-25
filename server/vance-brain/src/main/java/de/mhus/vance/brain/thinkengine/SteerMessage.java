package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.attachment.AttachmentRef;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.thinkprocess.PeerEventType;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ToolCallStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An inbound message delivered to {@link ThinkEngine#steer}. Every source of
 * input — user typing in the chat, a sibling-process emitting an event, an
 * async tool result, a direct client command — funnels into this sealed
 * hierarchy so engines can pattern-match exhaustively.
 *
 * <p>The persistent form lives in
 * {@code de.mhus.vance.shared.thinkprocess.PendingMessageDocument}; see
 * {@code SteerMessageCodec} for the bidirectional translation.
 */
public sealed interface SteerMessage
        permits SteerMessage.UserChatInput,
                SteerMessage.ProcessEvent,
                SteerMessage.Reply,
                SteerMessage.ToolResult,
                SteerMessage.ExternalCommand,
                SteerMessage.InboxAnswer,
                SteerMessage.PeerEvent {

    /** When the message was produced. */
    Instant at();

    /** Free-form key for retry-safe delivery; may be {@code null}. */
    @Nullable String idempotencyKey();

    /**
     * A user-typed chat message.
     *
     * @param at              timestamp when the client sent the message
     * @param idempotencyKey  optional, for client retries
     * @param fromUser        {@code UserDocument.name} of the sender, or
     *                        {@code "process:<id>"} when an orchestrator
     *                        steers a child via a tool call
     * @param content         the typed text
     * @param attachments     refs to {@code DocumentDocument}s the user
     *                        uploaded with this turn — never {@code null},
     *                        empty list when text-only. Resolution
     *                        happens at engine-drain time
     *                        (cross-project access blocked there).
     * @param voiceMode       {@code true} when the client expects a
     *                        TTS-friendly reply for this single turn
     *                        (speaker or talk-mode on). Engines render
     *                        a voice-block in their system prompt
     *                        when this is set. See
     *                        {@code specification/voice-mode.md}.
     */
    record UserChatInput(
            Instant at,
            @Nullable String idempotencyKey,
            String fromUser,
            @Nullable String fromUserDisplayName,
            String content,
            List<AttachmentRef> attachments,
            boolean voiceMode) implements SteerMessage {

        /**
         * Compact-form null-safety: {@code attachments == null} is
         * coerced to the empty list so consumers never need to
         * defend. The list is also defensively copied so the record
         * stays immutable in spirit even if the caller hands in a
         * mutable list.
         */
        public UserChatInput {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        /**
         * Backward-compatible constructor for the many call sites that
         * predate multimodal attachments. Equivalent to passing
         * {@link List#of()} for {@code attachments}, {@code false}
         * for {@code voiceMode}, and {@code null} for the display name.
         */
        public UserChatInput(
                Instant at,
                @Nullable String idempotencyKey,
                String fromUser,
                String content) {
            this(at, idempotencyKey, fromUser, null, content, List.of(), false);
        }

        /**
         * Backward-compatible constructor for callers that already
         * carry attachments but predate voice mode. Equivalent to
         * passing {@code false} for {@code voiceMode} and {@code null}
         * for the display name.
         */
        public UserChatInput(
                Instant at,
                @Nullable String idempotencyKey,
                String fromUser,
                String content,
                List<AttachmentRef> attachments) {
            this(at, idempotencyKey, fromUser, null, content, attachments, false);
        }

        /**
         * Backward-compatible constructor for callers that predate
         * the multi-user-session display-name field.
         */
        public UserChatInput(
                Instant at,
                @Nullable String idempotencyKey,
                String fromUser,
                String content,
                List<AttachmentRef> attachments,
                boolean voiceMode) {
            this(at, idempotencyKey, fromUser, null, content, attachments, voiceMode);
        }
    }

    /**
     * A sibling or child process reports a life-cycle / progress event.
     * Routed automatically by the brain when a process changes status
     * (terminal transitions) or when an engine pushes an explicit
     * summary.
     *
     * <p>The {@code eventId} is the stable handle a parent engine uses
     * to refer to this specific event (e.g. Arthur's RELAY {@code
     * eventRef}). It is generated when the event is queued and
     * persisted alongside it, so a drained event keeps the same id
     * across the codec round-trip. {@code null} only on legacy queued
     * events from before the field existed.
     *
     * <p>{@code inResponseToAt} is the timestamp of the user-input
     * turn the emitting worker was processing when it produced this
     * event — set by {@code ParentNotificationListener} from the
     * worker's chat history. Lets the receiving engine tell a fresh
     * reply from a stale one without having to guess from wall-clock
     * timing alone. {@code null} for engine-driven {@code SUMMARY}
     * events or when no triggering user-input could be identified.
     *
     * @param sourceProcessId Mongo id of the emitting process
     * @param type            event flavor — see {@link ProcessEventType}
     * @param humanSummary    short text suitable for direct LLM consumption
     * @param payload         optional structured side-channel data
     * @param eventId         stable handle for this event (UUID), used
     *                        by parent engines to reference it in
     *                        follow-up actions like Arthur's
     *                        {@code RELAY eventRef}
     * @param inResponseToAt  timestamp of the user-input turn the
     *                        emitting worker was responding to, or
     *                        {@code null} when not applicable
     */
    record ProcessEvent(
            Instant at,
            @Nullable String idempotencyKey,
            String sourceProcessId,
            ProcessEventType type,
            @Nullable String humanSummary,
            @Nullable Map<String, Object> payload,
            @Nullable String eventId,
            @Nullable Instant inResponseToAt) implements SteerMessage {
    }

    /**
     * A child process emitted a semantic reply — its complete answer
     * for one turn. Distinct from {@link ProcessEvent} which carries
     * pure lifecycle markers (DONE/FAILED/STOPPED). The parent's
     * engine receives this on its next lane-turn via
     * {@link ThinkEngineContext#drainPending()} and decides what to
     * do with the content (RELAY to user, aggregate into its own
     * state, …).
     *
     * <p>Multiple Replies from the same worker over its lifetime are
     * allowed, but each must be a self-contained result the parent can
     * interpret independently — no incremental fragments. For live
     * progress, engines emit {@code STATUS}/{@code METRICS}/{@code PLAN}
     * via {@code ProgressEmitter} instead. See
     * {@code planning/process-engine-reply-channel.md}.
     *
     * @param sourceProcessId Mongo id of the emitting worker
     * @param sourceProcessName cached display name of the worker; the
     *                          parent renders this directly without an
     *                          extra lookup
     * @param content          full reply text
     * @param inResponseToAt   timestamp of the user-input turn the
     *                         worker was responding to, or
     *                         {@code null} for engine-driven replies
     * @param payload          optional structured side-channel data
     */
    record Reply(
            Instant at,
            @Nullable String idempotencyKey,
            String sourceProcessId,
            @Nullable String sourceProcessName,
            String content,
            @Nullable Instant inResponseToAt,
            @Nullable Map<String, Object> payload) implements SteerMessage {
    }

    /**
     * Result of an asynchronously-dispatched tool call. The matching
     * call is identified by {@link #toolCallId()}; engines map this
     * back to a previously-emitted tool-use block.
     */
    record ToolResult(
            Instant at,
            @Nullable String idempotencyKey,
            String toolCallId,
            String toolName,
            ToolCallStatus status,
            @Nullable Object result,
            @Nullable String error) implements SteerMessage {
    }

    /**
     * High-level command from the client (UI button, slash-command).
     * The engine decides how to react — sometimes by skipping the LLM
     * entirely.
     */
    record ExternalCommand(
            Instant at,
            @Nullable String idempotencyKey,
            String command,
            Map<String, Object> params) implements SteerMessage {
    }

    /**
     * The user (or an auto-resolver) answered an inbox item that
     * this process was blocked on. Routed by the brain-side
     * {@code InboxAnsweredListener}.
     *
     * @param inboxItemId Mongo id of the answered item
     * @param itemType    type of the original ask (helps the engine
     *                    pick the right handler branch)
     * @param answer      three-state payload (DECIDED + value, or
     *                    INSUFFICIENT_INFO/UNDECIDABLE + reason)
     */
    record InboxAnswer(
            Instant at,
            @Nullable String idempotencyKey,
            String inboxItemId,
            InboxItemType itemType,
            AnswerPayload answer) implements SteerMessage {
    }

    /**
     * A peer hub-process of the same user reports a relevant action
     * (project created, worker spawned, status changed). Hub-only —
     * regular worker engines never receive this. See
     * {@code specification/eddie-engine.md} §5.3.
     *
     * @param sourceEddieProcessId Mongo id of the emitting hub-process
     * @param userId               {@code UserDocument.name} that owns
     *                             both peer hubs
     * @param type                 event flavor — see {@link PeerEventType}
     * @param humanSummary         one-line text suitable for direct LLM
     *                             consumption
     * @param payload              optional structured side-channel data
     *                             (e.g. project name, process id)
     */
    record PeerEvent(
            Instant at,
            @Nullable String idempotencyKey,
            String sourceEddieProcessId,
            String userId,
            PeerEventType type,
            String humanSummary,
            @Nullable Map<String, Object> payload) implements SteerMessage {
    }
}
