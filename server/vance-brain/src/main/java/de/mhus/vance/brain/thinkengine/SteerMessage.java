package de.mhus.vance.brain.thinkengine;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * An inbound message delivered to {@link ThinkEngine#steer}. Every source of
 * input — user typing in the chat, a sibling-process emitting an event, an
 * async tool result, a direct client command — funnels into this sealed
 * hierarchy so engines can pattern-match exhaustively.
 *
 * <p>v1 ships only {@link UserChatInput}. {@code ProcessEvent},
 * {@code ToolResult}, and {@code ExternalCommand} will be added as the
 * corresponding features land (process orchestration, tool dispatch,
 * external commands).
 */
public sealed interface SteerMessage
        permits SteerMessage.UserChatInput {

    /** When the message was produced. */
    Instant at();

    /** Free-form key for retry-safe delivery; may be {@code null}. */
    @Nullable String idempotencyKey();

    /**
     * A user-typed chat message.
     *
     * @param at              timestamp when the client sent the message
     * @param idempotencyKey  optional, for client retries
     * @param fromUser        {@code UserDocument.name} of the sender
     * @param content         the typed text
     */
    record UserChatInput(
            Instant at,
            @Nullable String idempotencyKey,
            String fromUser,
            String content) implements SteerMessage {
    }
}
