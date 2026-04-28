package de.mhus.vance.api.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.ws.ProjectGroupSummary;
import de.mhus.vance.api.ws.ProjectSummary;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response of {@code GET /brain/{tenant}/projects} — every project group and
 * every project in the caller's tenant in a single round-trip. Used by the
 * Web-UI's project selector. Ordering: project groups by name, projects by
 * name within their group; ungrouped projects are emitted last with a
 * {@code null projectGroupId}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("projects")
public class TenantProjectsResponse {

    @Builder.Default
    private List<ProjectGroupSummary> groups = new ArrayList<>();

    @Builder.Default
    private List<ProjectSummary> projects = new ArrayList<>();
}
