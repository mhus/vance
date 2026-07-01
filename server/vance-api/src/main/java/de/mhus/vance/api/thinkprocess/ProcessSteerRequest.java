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

    /**
     * Active-app hint for this single turn. When the client is showing
     * a folder-level app (Calendar / Kanban / Slideshow …) in the
     * editor that sent this turn, this carries
     * {@code { folder, app }} so the engine prompt can render a
     * context block via the {@code activeApp} Pebble variable plus a
     * dynamic {@code appInstructions} markdown chunk from the app's
     * {@code VanceApplication.promptInject(...)}.
     *
     * <p>Per-message, not session state — the user may flip between
     * the app view and other docs mid-conversation. {@code null} from
     * old clients (or from any turn where no app is open) leaves the
     * prompt block unrendered.
     *
     * <p>See {@code planning/apps-in-cortex-and-live.md} §5.
     */
    private @Nullable ActiveAppContext activeApp;

    /**
     * Id of the document the sending Cortex view has "bound" to the
     * chat for this turn (the {@code bind file} affordance). Per-message
     * LLM context — the engine resolves the id, inlines the document so
     * the agent can see the file the user is working on, and does not
     * persist it. {@code null} when nothing is bound or from clients
     * that don't have a Cortex view.
     *
     * <p>Deliberately travels with the steer instead of via persisted
     * session state: "which file the agent should see right now" is a
     * per-turn signal, not durable session status.
     */
    private @Nullable String boundDocumentId;

    /**
     * Character range selected inside {@link #boundDocumentId} at send
     * time. Per-message LLM context — the engine surfaces "the user has
     * a selection [from:to]" to the prompt; the model reads the content
     * on demand via {@code doc_get_selection}. {@code null} when nothing
     * is selected.
     */
    private @Nullable BoundDocSelection boundDocSelection;
}
