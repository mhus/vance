package de.mhus.vance.brain.projects;

import de.mhus.vance.api.projects.TenantProjectsResponse;
import de.mhus.vance.api.ws.ProjectGroupSummary;
import de.mhus.vance.api.ws.ProjectSummary;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.projectgroup.ProjectGroupDocument;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only REST view of the caller's tenant projects, used by the Web-UI's
 * project selector. The same data is also available over WebSocket via
 * {@code project-list} / {@code projectgroup-list} — REST is the convenience
 * channel, see {@code specification/web-ui.md} §4.
 *
 * <p>Tenant in the path is validated by {@link
 * de.mhus.vance.brain.access.BrainAccessFilter} against the JWT's
 * {@code tid} claim before requests reach this controller.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectGroupService projectGroupService;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/projects")
    public TenantProjectsResponse listProjectsAndGroups(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.READ);
        List<ProjectGroupSummary> groups = projectGroupService.all(tenant).stream()
                .sorted(Comparator.comparing(ProjectGroupDocument::getName))
                .map(ProjectController::toSummary)
                .toList();

        List<ProjectSummary> projects = projectService.all(tenant).stream()
                .sorted(Comparator
                        .comparing((ProjectDocument p) -> p.getProjectGroupId() == null ? "￿" : p.getProjectGroupId())
                        .thenComparing(ProjectDocument::getName))
                .map(ProjectController::toSummary)
                .toList();

        return TenantProjectsResponse.builder()
                .groups(groups)
                .projects(projects)
                .build();
    }

    private static ProjectGroupSummary toSummary(ProjectGroupDocument doc) {
        return ProjectGroupSummary.builder()
                .name(doc.getName())
                .title(doc.getTitle())
                .enabled(doc.isEnabled())
                .build();
    }

    private static ProjectSummary toSummary(ProjectDocument doc) {
        return ProjectSummary.builder()
                .name(doc.getName())
                .title(doc.getTitle())
                .projectGroupId(doc.getProjectGroupId())
                .enabled(doc.isEnabled())
                .build();
    }
}
