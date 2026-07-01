package de.mhus.vance.api.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /brain/{tenant}/session-groups/assign}.
 *
 * <p>Moves a session into {@code groupName} within the caller's
 * {@code (tenant, project, user)} scope, removing it from any other group
 * first (a session belongs to at most one of the user's groups). A
 * {@code null} {@code groupName} means "ungroup" — remove from all groups.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("session")
public class SessionGroupAssignRequest {

    @NotBlank
    private String sessionId;

    /** Target group name, or {@code null} to ungroup. */
    private @Nullable String groupName;
}
