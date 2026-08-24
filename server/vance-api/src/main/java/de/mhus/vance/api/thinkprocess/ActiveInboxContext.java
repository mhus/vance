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
 * Per-message hint that the reader has an inbox thread open beside this chat,
 * and possibly picked one contribution in it.
 *
 * <p><b>Why this is not {@link ActiveAppContext}.</b> That one names a
 * folder-level app and is resolved through the {@code VanceApplication}
 * registry — it needs an {@code _app.yaml} manifest in a project. The inbox has
 * neither: it is tenant-scoped and is not a document. Borrowing the app channel
 * would have meant registering an application that does not exist, which would
 * then show up in app listings, the applications REST surface and as a share
 * target. Two different things, two fields.
 *
 * <p>Rides with each {@code process-steer} request, last-message-wins, exposed
 * to the prompt template as the Pebble variable {@code activeInbox}. Per-message
 * and never persisted: the reader clicks through a list, and each turn says
 * where they were when they hit send.
 *
 * <p>Deliberately only ids. The thread's content is fetched with
 * {@code thread_get} when the model actually needs it — the same split Cortex
 * uses, where {@code BoundDocSelection} ships a range and the text is read on
 * demand. Pushing the body of whatever row the reader last clicked would put
 * foreign text in every turn's prompt whether it mattered or not.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class ActiveInboxContext {

    /** The thread the reader has open in the inbox panel. Required. */
    @NotBlank
    private String threadId;

    /**
     * The one contribution the reader picked inside that thread, or
     * {@code null} when they have the thread open without singling anything out.
     */
    @Nullable
    private String messageId;
}
