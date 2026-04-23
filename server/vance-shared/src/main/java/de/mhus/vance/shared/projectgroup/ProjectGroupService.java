package de.mhus.vance.shared.projectgroup;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Project-group lifecycle and lookup — the one entry point to project-group data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectGroupService {

    private final ProjectGroupRepository repository;

    public Optional<ProjectGroupDocument> findByTenantAndName(String tenantId, String name) {
        return repository.findByTenantIdAndName(tenantId, name);
    }

    public boolean existsByTenantAndName(String tenantId, String name) {
        return repository.existsByTenantIdAndName(tenantId, name);
    }

    public List<ProjectGroupDocument> all(String tenantId) {
        return repository.findByTenantId(tenantId);
    }

    /**
     * Creates a project group inside {@code tenantId}. Throws
     * {@link ProjectGroupAlreadyExistsException} if a group with the same
     * {@code name} already lives in that tenant.
     */
    public ProjectGroupDocument create(String tenantId, String name, @Nullable String title) {
        if (repository.existsByTenantIdAndName(tenantId, name)) {
            throw new ProjectGroupAlreadyExistsException(
                    "Project group '" + name + "' already exists in tenant '" + tenantId + "'");
        }
        ProjectGroupDocument group = ProjectGroupDocument.builder()
                .tenantId(tenantId)
                .name(name)
                .title(title)
                .enabled(true)
                .build();
        ProjectGroupDocument saved = repository.save(group);
        log.info("Created project group tenantId='{}' name='{}' id='{}'",
                saved.getTenantId(), saved.getName(), saved.getId());
        return saved;
    }

    public static class ProjectGroupAlreadyExistsException extends RuntimeException {
        public ProjectGroupAlreadyExistsException(String message) {
            super(message);
        }
    }
}
