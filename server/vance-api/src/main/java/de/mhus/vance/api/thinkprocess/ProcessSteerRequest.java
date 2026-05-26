package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.attachment.AttachmentRef;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code process-steer} — client delivers a user chat message to a
 * think-process in its bound session.
 *
 * <p>v1 only supports plain user chat text. Other steer-message kinds
 * (tool results, external commands) get their own transports once those
 * features exist.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ProcessSteerRequest {

    /** Name of the target process within the session. */
    @NotBlank
    private String processName;

    /** Chat content. */
    @NotBlank
    private String content;

    /** Optional idempotency key for client retries. */
    private @Nullable String idempotencyKey;

    /**
     * Optional editor-context metadata when the client is wired to an IDE
     * plugin (foot {@code --intellij-claude}). The brain renders this into
     * the prompt as {@code <ide-at-mention/>} and {@code <ide-selection/>}
     * hints. {@code null} when no IDE is attached.
     */
    private @Nullable IdeContext ideContext;

    /**
     * Optional multimodal attachments riding with the user-text turn —
     * each item points at a {@code DocumentDocument} the user uploaded
     * into the project (image, PDF, …). The brain resolves the refs
     * against the caller's project scope (tenant + project derived from
     * the WS session, never trusted from the client) and feeds the
     * binary content to the LLM as image / pdf / text content blocks.
     *
     * <p>Empty / {@code null} means "no attachments — text-only turn".
     * Attachments only ride on the turn that submitted them; future
     * turns of the same conversation see the chat history without the
     * attachments. The user must re-attach to refer back to them.
     */
    private @Nullable List<AttachmentRef> attachments;

    /**
     * Voice-mode flag for this single turn. {@code true} when the
     * client expects a TTS-friendly reply (speaker on, talk-mode on,
     * or any future voice-output mode). The brain reaches this flag
     * through to the engine prompt via the Pebble variable
     * {@code voiceMode} — engines render a separate voice-block when
     * the flag is set (kürzere Outputs, Markdown-Konvention für
     * Speak-vs-show-Trennung, STT-Input-Tolerance).
     *
     * <p>Per-message, not session state — the user may toggle voice
     * mode mid-conversation; each turn carries its own flag.
     *
     * <p>{@code null} from old clients is treated as {@code false}.
     * See {@code specification/voice-mode.md}.
     */
    private @Nullable Boolean voiceMode;
}
