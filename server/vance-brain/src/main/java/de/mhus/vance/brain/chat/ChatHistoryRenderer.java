package de.mhus.vance.brain.chat;

import de.mhus.vance.shared.chat.ChatMessageDocument;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
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
 * <p>SYSTEM turns are passed through verbatim. ASSISTANT turns are
 * passed through plus — when the turn had failed tool calls — the
 * failure block described in {@link #renderAssistant}.
 */
public final class ChatHistoryRenderer {

    /**
     * Opens the replayed failure block. Phrased as a fact about the
     * past turn, not as an instruction, so it cannot be mistaken for
     * a new task.
     */
    static final String FAILURE_HEADER =
            "[vance] Tool calls that FAILED in this turn (they had no effect; "
                    + "do not assume the work described above actually happened):";

    /**
     * Opens the replayed reference line. Phrased as a fact about the past
     * turn for the same reason the failure block is: it describes what the
     * message pointed at, and must not read as a new instruction to go and
     * fetch it.
     */
    static final String REFERENCE_HEADER = "[vance] This message pointed at:";

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
            case USER -> UserMessage.from(
                    renderUser(msg, applySenderPrefix(msg, msg.getContent(), collabActive)));
            case ASSISTANT -> AiMessage.from(renderAssistant(msg));
            case SYSTEM -> SystemMessage.from(msg.getContent());
        };
    }

    /**
     * Renders a USER turn for LLM replay: its content, plus the reference
     * line when the message pointed at something (see
     * {@link ChatMessageDocument#META_SELECTION_REFERENCE}).
     *
     * <p>Why this is needed at all: the app selection that produced the
     * reference travels as a <em>per-turn</em> hint and is deliberately
     * dropped afterwards — carrying it forward would claim the reader is
     * still looking at something they left. But the sentence stays in the
     * history, and without its antecedent "tell me more about the selected
     * case" replays as a pronoun pointing at nothing. Appending the line at
     * replay time keeps the record honest without editing what the user
     * wrote, exactly like the failure block on the assistant side.
     *
     * <p>Addresses are listed, not explained: {@code vance:} is followed
     * with the owning app's own read-tool (the handle after {@code ?entry=}
     * is what that tool takes), {@code http(s)} with {@code web_fetch}. A
     * per-message instruction to that effect would repeat itself once per
     * referring turn for no gain.
     */
    public static String renderUser(ChatMessageDocument msg, String body) {
        var ref = msg.selectionReference();
        if (ref == null) return body;
        StringBuilder sb = new StringBuilder(body);
        if (!body.isEmpty()) sb.append("\n\n");
        sb.append(REFERENCE_HEADER).append(" \"").append(ref.getLabel()).append('"');
        if (ref.getVanceUri() != null) sb.append(" — ").append(ref.getVanceUri());
        if (ref.getUrl() != null) sb.append(" — ").append(ref.getUrl());
        return sb.toString();
    }

    /**
     * Renders an ASSISTANT turn for LLM replay: its persisted content,
     * plus a failure block for every tool call that failed during that
     * turn (see {@link ChatMessageDocument#META_TOOL_FAILURES}).
     *
     * <p>Only tool <em>results</em> are absent from persisted history —
     * so a turn in which a write failed replays as the model's own
     * summary of that turn and nothing else. If the summary claimed
     * success, every later turn inherits the false claim with no way to
     * check it. The block is appended at replay time from persisted
     * metadata, so it is deterministic (prompt caching stays stable) and
     * never touches the content the user sees in the chat.
     *
     * <p>Turns without failures render exactly as before — byte for byte.
     */
    public static String renderAssistant(ChatMessageDocument msg) {
        String body = msg.getContent() == null ? "" : msg.getContent();
        List<String> failures = msg.toolFailures();
        if (failures.isEmpty()) return body;
        StringBuilder sb = new StringBuilder(body);
        if (!body.isEmpty()) sb.append("\n\n");
        sb.append(FAILURE_HEADER);
        for (String f : failures) {
            sb.append("\n- ").append(f);
        }
        return sb.toString();
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
