package de.mhus.vance.api.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/admin/projects}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("projects")
public class ProjectCreateRequest {

    @NotBlank
    @Pattern(regexp = "^[a-z0-9][a-z0-9_-]*$",
            message = "must be lower-case alphanumerics with optional '-' or '_'")
    private String name;

    private @Nullable String title;

    /** Optional — name of the {@code ProjectGroupDocument} this project lives in. */
    private @Nullable String projectGroupId;

    @Builder.Default
    private List<String> teamIds = new ArrayList<>();

    /**
     * Optional — name of an entry in the tenant-wide project-kits
     * catalog (spec: {@code project-kits-catalog.md}). When set, the
     * catalog entry is resolved and the referenced kit is installed
     * into the new project right after creation, in the same request.
     * Unknown names fail the whole call so the operator never gets a
     * half-finished project. {@code null} or blank → no kit install.
     */
    private @Nullable String kitName;
}
