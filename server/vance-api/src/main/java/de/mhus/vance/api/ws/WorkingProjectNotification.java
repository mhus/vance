package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload for the server-initiated
 * {@link MessageType#WORKING_PROJECT_CHANGED} frame. Notifies every
 * connection of the session that the chat-process's working-project
 * pointer ("spot" — which project Eddie currently coordinates) moved.
 *
 * <p>Pushed on every spot mutation regardless of origin — the LLM
 * {@code project_switch} tool, the WS {@code project-switch} request, or
 * Eddie's DELEGATE side-effect — plus once per welcome / resume so a
 * reconnecting client renders the current focus without a round-trip.
 *
 * <p>{@code workingProject} is {@code null} when the spot was cleared.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class WorkingProjectNotification {

    /** Session the notification is addressed to. */
    private String sessionId;

    /** New spot ({@code ProjectDocument.name}) — {@code null} when cleared. */
    private @Nullable String workingProject;
}
