package de.mhus.vance.shared.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Project lifecycle and lookup — the one entry point to project data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final ProjectRepository repository;

    public Optional<ProjectDocument> findByTenantAndName(String tenantId, String name) {
        return repository.findByTenantIdAndName(tenantId, name);
    }

    public boolean existsByTenantAndName(String tenantId, String name) {
        return repository.existsByTenantIdAndName(tenantId, name);
    }

    public List<ProjectDocument> all(String tenantId) {
        return repository.findByTenantId(tenantId);
    }

    public List<ProjectDocument> byGroup(String tenantId, String projectGroupId) {
        return repository.findByTenantIdAndProjectGroupId(tenantId, projectGroupId);
    }

    public List<ProjectDocument> byTeam(String tenantId, String teamId) {
        return repository.findByTenantIdAndTeamIdsContaining(tenantId, teamId);
    }

    /**
     * Creates a project inside {@code tenantId}. {@code projectGroupId} is
     * optional; {@code teamIds} may be empty. Throws
     * {@link ProjectAlreadyExistsException} if a project with the same
     * {@code name} already lives in that tenant.
     */
    public ProjectDocument create(
            String tenantId,
            String name,
            @Nullable String title,
            @Nullable String projectGroupId,
            @Nullable List<String> teamIds) {
        if (repository.existsByTenantIdAndName(tenantId, name)) {
            throw new ProjectAlreadyExistsException(
                    "Project '" + name + "' already exists in tenant '" + tenantId + "'");
        }
        ProjectDocument project = ProjectDocument.builder()
                .tenantId(tenantId)
                .name(name)
                .title(title)
                .projectGroupId(projectGroupId)
                .teamIds(teamIds == null ? new ArrayList<>() : new ArrayList<>(teamIds))
                .enabled(true)
                .build();
        ProjectDocument saved = repository.save(project);
        log.info("Created project tenantId='{}' name='{}' id='{}'",
                saved.getTenantId(), saved.getName(), saved.getId());
        return saved;
    }

    public static class ProjectAlreadyExistsException extends RuntimeException {
        public ProjectAlreadyExistsException(String message) {
            super(message);
        }
    }
}
