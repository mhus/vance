package de.mhus.vance.brain.projects;

import de.mhus.vance.api.projects.ProjectGroupCreateRequest;
import de.mhus.vance.api.projects.ProjectGroupUpdateRequest;
import de.mhus.vance.api.ws.ProjectGroupSummary;
import de.mhus.vance.shared.projectgroup.ProjectGroupDocument;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
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
 * Admin CRUD for project groups.
 *
 * <p>Tenant in the path is validated by
 * {@link de.mhus.vance.brain.access.BrainAccessFilter} against the JWT's
 * {@code tid} claim before requests reach this controller.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/project-groups")
@RequiredArgsConstructor
@Slf4j
public class ProjectGroupAdminController {

    private final ProjectGroupService projectGroupService;

    @GetMapping
    public List<ProjectGroupSummary> list(@PathVariable("tenant") String tenant) {
        return projectGroupService.all(tenant).stream()
                .sorted(Comparator.comparing(ProjectGroupDocument::getName))
                .map(ProjectGroupAdminController::toSummary)
                .toList();
    }

    @PostMapping
    public ResponseEntity<ProjectGroupSummary> create(
            @PathVariable("tenant") String tenant,
            @Valid @RequestBody ProjectGroupCreateRequest request) {
        try {
            ProjectGroupDocument saved = projectGroupService.create(
                    tenant, request.getName(), request.getTitle());
            return ResponseEntity.status(HttpStatus.CREATED).body(toSummary(saved));
        } catch (ProjectGroupService.ProjectGroupAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PutMapping("/{name}")
    public ProjectGroupSummary update(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @Valid @RequestBody ProjectGroupUpdateRequest request) {
        try {
            ProjectGroupDocument saved = projectGroupService.update(
                    tenant, name, request.getTitle(), request.getEnabled());
            return toSummary(saved);
        } catch (ProjectGroupService.ProjectGroupNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name) {
        try {
            projectGroupService.delete(tenant, name);
            return ResponseEntity.noContent().build();
        } catch (ProjectGroupService.ProjectGroupNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (ProjectGroupService.ProjectGroupNotEmptyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private static ProjectGroupSummary toSummary(ProjectGroupDocument doc) {
        return ProjectGroupSummary.builder()
                .name(doc.getName())
                .title(doc.getTitle())
                .enabled(doc.isEnabled())
                .build();
    }
}
