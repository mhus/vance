package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

    /**
     * The thread the reader has open in the inbox panel. Required.
     *
     * <p>The pattern is the shape of an identifier the brain issued — Mongo
     * ObjectId, UUID, generated message id.
     *
     * <p><b>These constraints are documentation, not the gate.</b> This request
     * arrives over the WebSocket and is deserialized with
     * {@code objectMapper.convertValue}, where no bean validation runs. The
     * enforcing check is in {@code PromptContextBuilder.activeInbox}, and it has
     * to be there: that is the point where the value would otherwise be rendered
     * into a system-prompt sentence unwrapped.
     *
     * <p>Spelled out twice rather than pulled into a constant: the TS generator
     * turns every {@code static final} on an annotated class into an exported
     * const, and {@code @vance/generated}'s barrel is flat — a name as generic
     * as {@code ID_PATTERN} has no business in it.
     */
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
    private String threadId;

    /**
     * The one contribution the reader picked inside that thread, or
     * {@code null} when they have the thread open without singling anything out.
     */
    @Nullable
    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
    private String messageId;
}
