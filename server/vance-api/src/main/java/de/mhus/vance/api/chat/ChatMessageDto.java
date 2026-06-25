package de.mhus.vance.api.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Snapshot of a single persisted chat message for the chat-history REST
 * endpoint ({@code GET /brain/{tenant}/sessions/{id}/messages}).
 *
 * <p>Distinct from {@link ChatMessageAppendedData}, which is the push
 * notification fired when a message is persisted: this DTO is the pull
 * result for loading history on chat-editor mount.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("chat")
public class ChatMessageDto {

    private String messageId;

    private String thinkProcessId;

    private ChatRole role;

    private String content;

    private @Nullable Instant createdAt;

    /**
     * Optional structured metadata that travels alongside the
     * message content. Today this is used for ASK_USER picker
     * options (key {@code askUserOptions} → array of
     * {@code { label, description? }}); future engines may use it
     * for other typed side-channels. Picker-aware clients render
     * the options as buttons; clients that ignore the meta still
     * see the Markdown rendering in {@link #content}.
     */
    private @Nullable Map<String, Object> meta;

    /**
     * User-id of the sender for USER-role messages — see
     * {@code planning/multi-user-sessions.md} §3.5.
     *
     * <p>{@code null} for non-USER roles (ASSISTANT, SYSTEM) and for
     * legacy rows persisted before the multi-user fields existed.
     * Clients fall back to {@code session.userId} when {@code null}.
     */
    private @Nullable String senderUserId;

    /**
     * Display-name of the sender captured at write time — used by the
     * chat-UI to render speaker labels and color-coding without an
     * extra user lookup per turn. {@code null} for non-USER roles and
     * for legacy rows.
     */
    private @Nullable String senderDisplayName;

    /**
     * {@code true} when this USER turn explicitly addressed the agent
     * (mention) or the session was not in collaboration-mode at receive
     * time. {@code false} marks a background turn — persisted for
     * context but did not wake the agent.
     *
     * <p>Default {@code true} keeps backward compatibility: legacy 1:1
     * turns and every ASSISTANT/SYSTEM turn are always "addressed".
     */
    @Builder.Default
    private boolean addressedToAgent = true;
}
