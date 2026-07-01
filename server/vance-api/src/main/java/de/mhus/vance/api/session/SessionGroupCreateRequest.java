package de.mhus.vance.api.session;

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
 * Body of {@code POST /brain/{tenant}/session-groups}.
 *
 * <p>Session groups are scoped to {@code (tenant, project, user)}; the user
 * is taken from the authenticated request, not the body. {@code name} is the
 * technical, unique business identifier inside that scope (kebab-/lower-case
 * convention). {@code title} is the display name and may be {@code null}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("session")
public class SessionGroupCreateRequest {

    @NotBlank
    private String projectId;

    @NotBlank
    @Pattern(regexp = "^[a-z0-9][a-z0-9_-]*$",
            message = "must be lower-case alphanumerics with optional '-' or '_'")
    private String name;

    private @Nullable String title;
}
