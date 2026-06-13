package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/share/inbox}. Used by the
 * iOS Share-Extension to drop a shared item (text, URL, note) into
 * the authenticated user's inbox, scoped to the chosen project.
 *
 * <p>v1 carries text/URL payloads only. File attachments are a
 * follow-up — the extension currently uploads files as Documents
 * via the existing {@code /documents/upload} multipart endpoint
 * and only references the result here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxShareRequest {

    /** Project the share should land in. Must match the {@code name}
     *  of an existing {@code ProjectDocument} in the request's
     *  tenant; otherwise 404. */
    @NotBlank
    private String projectName;

    /** Optional short heading the user typed in the extension UI.
     *  Falls back to a generic "Shared item" when blank. */
    @Nullable
    private String title;

    /** Optional body / note text. Either {@link #body} or
     *  {@link #sharedUrl} (or both) should be set — an entirely
     *  empty share is still accepted but not useful. */
    @Nullable
    private String body;

    /** Optional URL that was the source of the share (e.g. Safari's
     *  active tab when the user picked "Share to Vance"). */
    @Nullable
    private String sharedUrl;
}
