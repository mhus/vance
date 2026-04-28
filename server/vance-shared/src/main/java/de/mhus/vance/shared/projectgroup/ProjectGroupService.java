package de.mhus.vance.shared.projectgroup;

import de.mhus.vance.shared.project.ProjectService;
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
    private final ProjectService projectService;

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

    /** Reserved name of the auto-created "Archived" group. */
    public static final String ARCHIVED_GROUP_NAME = "archived";

    /**
     * Returns the tenant's "archived" group, creating it on first call. The
     * group is just a regular project group with a fixed name; nothing in the
     * data model marks it as special — it is simply where archived projects
     * land so the editor can group them visually.
     */
    public ProjectGroupDocument ensureArchivedGroup(String tenantId) {
        return repository.findByTenantIdAndName(tenantId, ARCHIVED_GROUP_NAME)
                .orElseGet(() -> create(tenantId, ARCHIVED_GROUP_NAME, "Archived"));
    }

    /**
     * Patches mutable fields. {@code name} and {@code tenantId} are immutable.
     * {@code null} title/enabled means "leave as is".
     *
     * @throws ProjectGroupNotFoundException if the group does not exist
     */
    public ProjectGroupDocument update(
            String tenantId, String name, @Nullable String title, @Nullable Boolean enabled) {
        ProjectGroupDocument group = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new ProjectGroupNotFoundException(
                        "Project group '" + name + "' not found in tenant '" + tenantId + "'"));
        if (title != null) {
            group.setTitle(title);
        }
        if (enabled != null) {
            group.setEnabled(enabled);
        }
        ProjectGroupDocument saved = repository.save(group);
        log.info("Updated project group tenantId='{}' name='{}' title='{}' enabled={}",
                saved.getTenantId(), saved.getName(), saved.getTitle(), saved.isEnabled());
        return saved;
    }

    /**
     * Deletes a project group. The group must be empty — no project may
     * still reference it via {@code projectGroupId}, otherwise
     * {@link ProjectGroupNotEmptyException} is thrown. The reserved
     * {@value #ARCHIVED_GROUP_NAME} group cannot be deleted.
     */
    public void delete(String tenantId, String name) {
        if (ARCHIVED_GROUP_NAME.equals(name)) {
            throw new ProjectGroupReservedException(
                    "Project group '" + name + "' is reserved and cannot be deleted");
        }
        ProjectGroupDocument group = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new ProjectGroupNotFoundException(
                        "Project group '" + name + "' not found in tenant '" + tenantId + "'"));
        if (projectService.existsByGroup(tenantId, name)) {
            throw new ProjectGroupNotEmptyException(
                    "Project group '" + name + "' still has projects assigned");
        }
        repository.delete(group);
        log.info("Deleted project group tenantId='{}' name='{}'", tenantId, name);
    }

    public static class ProjectGroupAlreadyExistsException extends RuntimeException {
        public ProjectGroupAlreadyExistsException(String message) {
            super(message);
        }
    }

    public static class ProjectGroupNotFoundException extends RuntimeException {
        public ProjectGroupNotFoundException(String message) {
            super(message);
        }
    }

    public static class ProjectGroupNotEmptyException extends RuntimeException {
        public ProjectGroupNotEmptyException(String message) {
            super(message);
        }
    }

    public static class ProjectGroupReservedException extends RuntimeException {
        public ProjectGroupReservedException(String message) {
            super(message);
        }
    }
}
