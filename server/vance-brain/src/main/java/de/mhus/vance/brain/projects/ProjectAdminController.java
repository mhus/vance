package de.mhus.vance.brain.projects;

import de.mhus.vance.api.projects.ProjectCreateRequest;
import de.mhus.vance.api.projects.ProjectDto;
import de.mhus.vance.api.projects.ProjectUpdateRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.projectgroup.ProjectGroupDocument;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin CRUD for projects.
 *
 * <p>{@code DELETE} archives the project (status → ARCHIVED, group → reserved
 * "archived" group) instead of hard-deleting — orphan settings, documents and
 * sessions stay reachable.
 *
 * <p>Tenant in the path is validated by
 * {@link de.mhus.vance.brain.access.BrainAccessFilter} against the JWT's
 * {@code tid} claim before requests reach this controller.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/projects")
@RequiredArgsConstructor
@Slf4j
public class ProjectAdminController {

    private final ProjectService projectService;
    private final ProjectGroupService projectGroupService;
    private final RequestAuthority authority;

    @GetMapping
    public List<ProjectDto> list(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        return projectService.all(tenant).stream()
                .sorted(Comparator
                        .comparing((ProjectDocument p) -> p.getProjectGroupId() == null ? "￿" : p.getProjectGroupId())
                        .thenComparing(ProjectDocument::getName))
                .map(ProjectAdminController::toDto)
                .toList();
    }

    @PostMapping
    public ResponseEntity<ProjectDto> create(
            @PathVariable("tenant") String tenant,
            @Valid @RequestBody ProjectCreateRequest request,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        try {
            ProjectDocument saved = projectService.create(
                    tenant,
                    request.getName(),
                    request.getTitle(),
                    request.getProjectGroupId(),
                    request.getTeamIds());
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
        } catch (ProjectService.ProjectAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PutMapping("/{name}")
    public ProjectDto update(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @Valid @RequestBody ProjectUpdateRequest request,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, name), Action.ADMIN);
        try {
            ProjectDocument saved = projectService.update(
                    tenant,
                    name,
                    request.getTitle(),
                    request.getEnabled(),
                    request.getProjectGroupId(),
                    request.isClearProjectGroup(),
                    request.getTeamIds());
            return toDto(saved);
        } catch (ProjectService.ProjectNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/{name}")
    public ProjectDto close(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, name), Action.ADMIN);
        try {
            ProjectGroupDocument archivedGroup = projectGroupService.ensureArchivedGroup(tenant);
            ProjectDocument saved = projectService.close(tenant, name, archivedGroup.getName());
            return toDto(saved);
        } catch (ProjectService.ProjectNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private static ProjectDto toDto(ProjectDocument doc) {
        return ProjectDto.builder()
                .name(doc.getName())
                .title(doc.getTitle())
                .projectGroupId(doc.getProjectGroupId())
                .teamIds(doc.getTeamIds())
                .enabled(doc.isEnabled())
                .status(doc.getStatus() == null ? null : doc.getStatus().name())
                .podIp(doc.getPodIp())
                .claimedAt(doc.getClaimedAt())
                .createdAt(doc.getCreatedAt())
                .build();
    }
}
