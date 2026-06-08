package de.mhus.vance.api.followup;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/follow-up/{project}}.
 *
 * <p>Two structural modes, selected by presence of {@code cursor}:
 *
 * <ul>
 *   <li><b>Edit mode</b> — {@code cursor} is set. The caller is
 *       editing {@code text} and wants suggestions for what could come
 *       next at the cursor position. The service splits {@code text}
 *       at {@code cursor} into {@code textBefore}/{@code textAfter}
 *       and feeds both to the prompt.</li>
 *   <li><b>Reply mode</b> — {@code cursor} is {@code null}. The caller
 *       wants suggestions that <em>react</em> to {@code text} (e.g. a
 *       follow-up question to an assistant's last message). The
 *       service passes the whole {@code text} as {@code precedingContext}
 *       to the prompt.</li>
 * </ul>
 *
 * <p>Tenant and project are taken from the path; {@code _tenant} is
 * the conventional "no project context" default.
 *
 * <p>{@code mode} is an optional free-form UI-surface hint
 * (e.g. {@code "chat-prompt"}, {@code "text-editor"},
 * {@code "chat-reply"}) — orthogonal to the edit/reply branch, kept
 * so callers can pass intent without us having to change the
 * contract for future specialisation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("followup")
public class FollowUpRequestDto {

    /** The full text. In edit mode the user is editing it; in reply
     *  mode it's the preceding context. Empty string is allowed in
     *  edit mode; in reply mode an empty text yields no useful
     *  suggestions. */
    @NotNull
    private String text;

    /**
     * Cursor position as character offset from the start of {@code text}.
     * When set, must be in {@code [0, text.length()]} — the service
     * splits the text at this offset and passes
     * {@code textBefore}/{@code textAfter} to the prompt
     * (<b>edit mode</b>).
     *
     * <p>{@code null} switches the service to <b>reply mode</b>: the
     * whole {@code text} is passed as {@code precedingContext}.
     */
    @Min(0)
    private @Nullable Integer cursor;

    /**
     * Maximum number of suggestions the caller wants back. Server caps
     * at a hard upper bound (see {@code FollowUpService.MAX_COUNT})
     * to keep LLM responses bounded. {@code 0} or negative is
     * rejected.
     */
    @Min(1)
    private int count;

    /**
     * Caller hint about the surrounding UI surface — e.g.
     * {@code "chat-prompt"}, {@code "text-editor"},
     * {@code "chat-reply"}. Optional; passed through to the prompt
     * for future specialisation. Orthogonal to the edit/reply branch.
     */
    private @Nullable String mode;
}
