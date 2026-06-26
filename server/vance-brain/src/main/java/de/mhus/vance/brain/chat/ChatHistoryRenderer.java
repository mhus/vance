package de.mhus.vance.brain.chat;

import de.mhus.vance.shared.chat.ChatMessageDocument;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.jspecify.annotations.Nullable;

/**
 * Converts a persisted {@link ChatMessageDocument} into the
 * langchain4j {@link ChatMessage} shape used by every engine's
 * LLM call.
 *
 * <p>USER turns are prepended with the sender's display name
 * ({@code "Alice: ..."}) when {@link ChatMessageDocument#getSenderDisplayName()}
 * is set — that's how the agent tells multi-user participants
 * apart at LLM-time. Legacy turns (no display name) pass through
 * unchanged, which preserves the 1:1 prompt shape every engine
 * relied on before multi-user landed.
 *
 * <p>Engine-default prompts that opt into multi-user awareness
 * tell the model that USER turns may carry a {@code "<Name>: "}
 * prefix and that the prefix is routing metadata, not part of
 * the user's actual content — see plan §5 / §6.
 *
 * <p>ASSISTANT and SYSTEM turns are passed through verbatim. They
 * have no sender identity beyond the role itself.
 */
public final class ChatHistoryRenderer {

    private ChatHistoryRenderer() {}

    /**
     * Converts {@code msg} to a langchain4j {@link ChatMessage} with
     * the current-turn collab flag taken into account.
     *
     * <p>USER turns prepend the display name only when
     * {@code collabActive} is true — solo (1:1) sessions keep the
     * prompt shape they always had, so existing recipes don't see
     * a regression. Pass {@code false} from engines that don't know
     * (or don't care about) the multi-user mode; the {@link
     * #toLangchain(ChatMessageDocument)} overload does exactly that.
     */
    public static ChatMessage toLangchain(ChatMessageDocument msg, boolean collabActive) {
        return switch (msg.getRole()) {
            case USER -> UserMessage.from(applySenderPrefix(msg, msg.getContent(), collabActive));
            case ASSISTANT -> AiMessage.from(msg.getContent());
            case SYSTEM -> SystemMessage.from(msg.getContent());
        };
    }

    /**
     * Backward-compatible overload — keeps the call sites that haven't
     * been wired to {@code collabActive} yet working with the
     * pre-multi-user shape (no prefix).
     */
    public static ChatMessage toLangchain(ChatMessageDocument msg) {
        return toLangchain(msg, false);
    }

    /**
     * Returns {@code content} prefixed with {@code "<DisplayName>: "}
     * when {@code collabActive} is true and the document carries a
     * display name; otherwise returns {@code content} unchanged.
     * Exposed for engine paths that build a USER message from raw text
     * + the same document for context (e.g. attachments path).
     */
    public static String applySenderPrefix(
            ChatMessageDocument msg, @Nullable String content, boolean collabActive) {
        String body = content == null ? "" : content;
        if (!collabActive) return body;
        String name = msg.getSenderDisplayName();
        if (name == null || name.isBlank()) return body;
        return name + ": " + body;
    }

    /**
     * Prefix variant for the in-flight USER turn (drained from
     * {@code SteerMessage.UserChatInput}) — the source isn't a
     * {@code ChatMessageDocument} yet, so the engine passes the
     * captured display name directly.
     */
    public static String applySenderPrefix(
            @Nullable String senderDisplayName, @Nullable String content, boolean collabActive) {
        String body = content == null ? "" : content;
        if (!collabActive) return body;
        if (senderDisplayName == null || senderDisplayName.isBlank()) return body;
        return senderDisplayName + ": " + body;
    }
}
