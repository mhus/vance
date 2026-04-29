package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.thinkprocess.PeerEventType;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ToolCallStatus;
import java.time.Instant;
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
     */
    record UserChatInput(
            Instant at,
            @Nullable String idempotencyKey,
            String fromUser,
            String content) implements SteerMessage {
    }

    /**
     * A sibling or child process reports a life-cycle / progress event.
     * Routed automatically by the brain when a process changes status
     * (terminal transitions) or when an engine pushes an explicit
     * summary.
     *
     * @param sourceProcessId Mongo id of the emitting process
     * @param type            event flavor — see {@link ProcessEventType}
     * @param humanSummary    short text suitable for direct LLM consumption
     * @param payload         optional structured side-channel data
     */
    record ProcessEvent(
            Instant at,
            @Nullable String idempotencyKey,
            String sourceProcessId,
            ProcessEventType type,
            @Nullable String humanSummary,
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
