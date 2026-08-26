package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Per-message hint that the user is currently viewing a folder-level
 * app ({@code kind: application} with a {@code app:} discriminator —
 * Calendar, Kanban, Slideshow, …) in the editor that sent this turn.
 *
 * <p>Rides with each {@code process-steer} request, analogous to
 * {@link ProcessSteerRequest#voiceMode}. Last-message-wins: the engine
 * drain picks the value off the most recent {@code UserChatInput} in
 * the pending queue and exposes it to the prompt template via the
 * Pebble variable {@code activeApp}, alongside an {@code appInstructions}
 * string the app's {@code VanceApplication.promptInject(...)} returned.
 *
 * <p>Per-message, not session state — open / close the app tab without
 * polluting any persistent server-side flag. {@code null} means "user
 * is not in an app right now".
 *
 * <p>See {@code planning/apps-in-cortex-and-live.md} §5.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ActiveAppContext {

    /**
     * Folder of the open app — the path of the {@code _app.yaml}
     * manifest minus the {@code /_app.yaml} suffix. Required.
     */
    @NotBlank
    private String folder;

    /**
     * App-type discriminator from {@code _app.yaml}'s {@code $meta.app}
     * (e.g. {@code calendar}, {@code kanban}, {@code slideshow}). The
     * brain uses this to resolve the matching
     * {@code VanceApplication}-SPI implementation. Required.
     */
    @NotBlank
    private String app;

    /**
     * Optional, app-computed selection hint for the current turn — a short
     * freeform string the app's UI produces to say what the user has selected
     * (e.g. a canvas board's selected node ids). The brain hands it to the
     * app's {@code VanceApplication.promptInject(...)}, which decides how to
     * phrase it for the model. {@code null} = nothing selected.
     */
    @Nullable
    private String selection;

    /**
     * The same selection as a <b>durable reference</b> — label plus the
     * address(es) it can be followed by. Where {@link #selection} is a
     * phrase for this turn's prompt and is then forgotten, this is what
     * gets persisted on the user's chat message, so a later turn can still
     * tell what "the selected entry" was after the reader scrolled away.
     *
     * <p>Declared by the app's own UI, because the app is the only place
     * that knows what a click on one of its cards means. {@code null} when
     * nothing is selected or the app has not opted in — persisting nothing
     * is the old behaviour, not an error.
     *
     * <p>See {@code specification/public/selection-reference.md}.
     */
    @Nullable
    private SelectionReference selectionRef;
}
