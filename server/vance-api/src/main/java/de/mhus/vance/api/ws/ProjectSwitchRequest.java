package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload for a {@link MessageType#PROJECT_SWITCH} request. Moves the
 * spot pointer ({@code ThinkProcessDocument.workingProjectId}) of the
 * bound session's chat process to the named foreign project. Used by
 * client-side {@code /project} shortcuts to set Eddie's focus without
 * going through an LLM round-trip.
 *
 * <p>The home project ({@code ThinkProcessDocument.projectId}) is
 * never changed by this request — only the spot moves.
 *
 * <p>{@code name} of {@code null} / blank / {@code "-"} clears the
 * spot (Eddie no longer coordinates any foreign project).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class ProjectSwitchRequest {

    /**
     * Target {@code ProjectDocument.name}, or {@code null}/blank to
     * clear the current spot.
     */
    private @Nullable String name;
}
