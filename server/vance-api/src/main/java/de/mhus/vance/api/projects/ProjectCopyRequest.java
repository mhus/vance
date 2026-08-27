package de.mhus.vance.api.projects;

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
 * Body of {@code POST /brain/{tenant}/admin/projects/{name}/copy}.
 *
 * <p>{@code name} is the <em>new</em> project; the source is in the path.
 * Title and group default to the source's when omitted, which is what makes
 * "copy this, call it X" a one-field request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("projects")
public class ProjectCopyRequest {

    @NotBlank
    @Pattern(regexp = "^[a-z0-9][a-z0-9_-]*$",
            message = "must be lower-case alphanumerics with optional '-' or '_'")
    private String name;

    /** Display title of the copy. {@code null} → the source's title. */
    private @Nullable String title;

    /** Project group of the copy. {@code null} → the source's group. */
    private @Nullable String projectGroupId;

    /**
     * Whether encrypted settings ({@code PASSWORD} / {@code HIDDEN}) are
     * carried over. Off by default, and deliberately so: a copy is usually
     * read by more people than the original, and this dialog is the only
     * place where anyone notices that the credentials came along. The keys
     * that were left behind are listed in the report.
     */
    private boolean includeSecrets;
}
