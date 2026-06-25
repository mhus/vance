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
     * Converts {@code msg} to a langchain4j {@link ChatMessage}.
     * USER turns prepend the display name when present.
     */
    public static ChatMessage toLangchain(ChatMessageDocument msg) {
        return switch (msg.getRole()) {
            case USER -> UserMessage.from(applySenderPrefix(msg, msg.getContent()));
            case ASSISTANT -> AiMessage.from(msg.getContent());
            case SYSTEM -> SystemMessage.from(msg.getContent());
        };
    }

    /**
     * Returns {@code content} prefixed with {@code "<DisplayName>: "}
     * when the document carries a display name; otherwise returns
     * {@code content} unchanged. Exposed for engine paths that build
     * a USER message from raw text + the same document for context.
     */
    public static String applySenderPrefix(ChatMessageDocument msg, @Nullable String content) {
        String name = msg.getSenderDisplayName();
        String body = content == null ? "" : content;
        if (name == null || name.isBlank()) return body;
        return name + ": " + body;
    }
}
