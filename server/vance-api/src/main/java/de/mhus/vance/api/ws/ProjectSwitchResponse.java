package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Response for {@link MessageType#PROJECT_SWITCH}. Carries the
 * post-update spot pointer so the client can confirm what is now
 * active. {@code workingProject} is {@code null} when the spot was
 * cleared.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class ProjectSwitchResponse {

    /** Effective spot ({@code ProjectDocument.name}) after the update — {@code null} when cleared. */
    private @Nullable String workingProject;
}
