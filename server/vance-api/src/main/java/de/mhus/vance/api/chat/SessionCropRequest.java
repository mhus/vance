package de.mhus.vance.api.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for the Modify/Crop mutation on a session's chat memory. Two
 * explicit, idempotent lists of message ids: {@code remove} takes messages
 * out of the LLM memory (marks {@code meta.kind=removed}); {@code restore}
 * brings previously-removed ones back. Both may be empty. A crop-from-point
 * is expressed by the client as a {@code remove} list of every message from
 * the chosen point onward.
 *
 * <p>See {@code specification/public/session-crop.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("chat")
public class SessionCropRequest {

    /** Message ids to remove from memory. Null/empty = nothing to remove. */
    private List<String> remove = List.of();

    /** Message ids to restore into memory. Null/empty = nothing to restore. */
    private List<String> restore = List.of();
}
