package de.mhus.vance.shared.project;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Project lifecycle and lookup — the one entry point to project data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    /**
     * Reserved name prefix for {@link ProjectKind#SYSTEM} projects.
     * Regular user projects may not start with this prefix — see
     * {@link #create(String, String, String, String, List, ProjectKind)}.
     */
    public static final String SYSTEM_NAME_PREFIX = "_";

    /** Field names — kept here so atomic queries don't drift. */
    private static final String F_TENANT = "tenantId";
    private static final String F_NAME = "name";
    private static final String F_STATUS = "status";
    private static final String F_POD_IP = "podIp";
    private static final String F_CLAIMED_AT = "claimedAt";

    private final ProjectRepository repository;
    private final MongoTemplate mongoTemplate;

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

    public boolean existsByGroup(String tenantId, String projectGroupId) {
        return repository.existsByTenantIdAndProjectGroupId(tenantId, projectGroupId);
    }

    public List<ProjectDocument> byTeam(String tenantId, String teamId) {
        return repository.findByTenantIdAndTeamIdsContaining(tenantId, teamId);
    }

    /**
     * Creates a {@link ProjectKind#NORMAL} project — see
     * {@link #create(String, String, String, String, List, ProjectKind)}.
     */
    public ProjectDocument create(
            String tenantId,
            String name,
            @Nullable String title,
            @Nullable String projectGroupId,
            @Nullable List<String> teamIds) {
        return create(tenantId, name, title, projectGroupId, teamIds, ProjectKind.NORMAL);
    }

    /**
     * Creates a project inside {@code tenantId}. {@code projectGroupId} is
     * optional; {@code teamIds} may be empty. {@code kind} is immutable after
     * creation — use {@link ProjectKind#SYSTEM} for hub/system projects (see
     * {@code specification/vance-engine.md} §2). Throws
     * {@link ProjectAlreadyExistsException} if a project with the same
     * {@code name} already lives in that tenant.
     */
    public ProjectDocument create(
            String tenantId,
            String name,
            @Nullable String title,
            @Nullable String projectGroupId,
            @Nullable List<String> teamIds,
            ProjectKind kind) {
        if (kind == ProjectKind.NORMAL && name.startsWith(SYSTEM_NAME_PREFIX)) {
            throw new ReservedProjectNameException(
                    "Project name '" + name + "' starts with the reserved '"
                            + SYSTEM_NAME_PREFIX + "' prefix — only SYSTEM projects "
                            + "may use that prefix");
        }
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
                .kind(kind)
                .build();
        ProjectDocument saved = repository.save(project);
        log.info("Created project tenantId='{}' name='{}' kind={} id='{}'",
                saved.getTenantId(), saved.getName(), saved.getKind(), saved.getId());
        return saved;
    }

    /**
     * Atomically claims {@code (tenantId, name)} for {@code podIp}: refreshes
     * {@code podIp} and {@code claimedAt}, leaves the lifecycle status alone
     * (lifecycle transitions go through {@link #markRecovering} /
     * {@link #markRunning} / etc.). Idempotent on the same pod, takes-over
     * from another pod (with a warning logged). Refuses to claim CLOSED
     * projects.
     *
     * @return the post-update document
     * @throws ProjectNotFoundException if the project does not exist
     * @throws ProjectClosedException   if the project is CLOSED
     */
    public ProjectDocument claim(String tenantId, String name, String podIp) {
        ProjectDocument current = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new ProjectNotFoundException(
                        "Project '" + name + "' not found in tenant '" + tenantId + "'"));
        if (current.getStatus() == ProjectStatus.CLOSED) {
            throw new ProjectClosedException(
                    "Project '" + name + "' is CLOSED — cannot claim");
        }
        Query query = new Query(Criteria.where(F_TENANT).is(tenantId).and(F_NAME).is(name));
        Update update = new Update()
                .set(F_POD_IP, podIp)
                .set(F_CLAIMED_AT, Instant.now());
        ProjectDocument updated = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                ProjectDocument.class);
        if (updated == null) {
            throw new ProjectNotFoundException(
                    "Project '" + name + "' disappeared during claim");
        }
        if (!Objects.equals(current.getPodIp(), podIp)) {
            log.info("Project '{}' claimed by pod '{}' (was '{}', status={})",
                    name, podIp, current.getPodIp(), current.getStatus());
        }
        return updated;
    }

    /**
     * Atomically transitions a project from one lifecycle status to another.
     * Returns the updated document if the transition won the race, throws
     * {@link ProjectStatusConflictException} if the document was in a
     * different status. Used by {@code ProjectLifecycleService} (vance-brain)
     * to drive the lifecycle.
     */
    public ProjectDocument transitionStatus(String tenantId, String name,
                                            ProjectStatus expected, ProjectStatus target) {
        Query query = new Query(Criteria.where(F_TENANT).is(tenantId)
                .and(F_NAME).is(name)
                .and(F_STATUS).is(expected));
        Update update = new Update().set(F_STATUS, target);
        ProjectDocument updated = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                ProjectDocument.class);
        if (updated == null) {
            ProjectDocument actual = repository.findByTenantIdAndName(tenantId, name)
                    .orElseThrow(() -> new ProjectNotFoundException(
                            "Project '" + name + "' not found in tenant '" + tenantId + "'"));
            throw new ProjectStatusConflictException(
                    "Project '" + name + "' status was " + actual.getStatus()
                            + ", expected " + expected + " for transition to " + target);
        }
        log.info("Project '{}' transition {} → {}", name, expected, target);
        return updated;
    }

    /**
     * Patches mutable fields. {@code name} and {@code tenantId} are immutable.
     * Pass {@code null} to leave a field untouched. To clear the project-group
     * assignment use {@code clearProjectGroup=true}.
     *
     * @throws ProjectNotFoundException if the project does not exist
     */
    public ProjectDocument update(
            String tenantId,
            String name,
            @Nullable String title,
            @Nullable Boolean enabled,
            @Nullable String projectGroupId,
            boolean clearProjectGroup,
            @Nullable List<String> teamIds) {
        ProjectDocument project = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new ProjectNotFoundException(
                        "Project '" + name + "' not found in tenant '" + tenantId + "'"));
        if (title != null) {
            project.setTitle(title);
        }
        if (enabled != null) {
            project.setEnabled(enabled);
        }
        if (clearProjectGroup) {
            project.setProjectGroupId(null);
        } else if (projectGroupId != null) {
            project.setProjectGroupId(projectGroupId);
        }
        if (teamIds != null) {
            project.setTeamIds(new ArrayList<>(teamIds));
        }
        ProjectDocument saved = repository.save(project);
        log.info("Updated project tenantId='{}' name='{}' title='{}' enabled={} groupId='{}'",
                saved.getTenantId(), saved.getName(), saved.getTitle(),
                saved.isEnabled(), saved.getProjectGroupId());
        return saved;
    }

    /**
     * Closes a project: status to {@link ProjectStatus#CLOSED} and
     * {@code projectGroupId} replaced by {@code closedGroupId}. Idempotent.
     *
     * <p>Refuses to close {@link ProjectKind#SYSTEM} projects — those host
     * infrastructure such as the per-user Vance Hub and must not disappear.
     * Workspace cleanup is the caller's responsibility (typically via
     * {@code ProjectLifecycleService.close} which disposes the workspace
     * first).
     *
     * @throws ProjectNotFoundException if the project does not exist
     * @throws SystemProjectProtectedException if the project is SYSTEM
     */
    public ProjectDocument close(String tenantId, String name, String closedGroupId) {
        ProjectDocument current = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new ProjectNotFoundException(
                        "Project '" + name + "' not found in tenant '" + tenantId + "'"));
        if (current.getKind() == ProjectKind.SYSTEM) {
            throw new SystemProjectProtectedException(
                    "Project '" + name + "' is SYSTEM — cannot close");
        }
        Query query = new Query(Criteria.where(F_TENANT).is(tenantId).and(F_NAME).is(name));
        Update update = new Update()
                .set(F_STATUS, ProjectStatus.CLOSED)
                .set("projectGroupId", closedGroupId);
        ProjectDocument updated = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                ProjectDocument.class);
        if (updated == null) {
            throw new ProjectNotFoundException(
                    "Project '" + name + "' disappeared during close");
        }
        log.info("Closed project tenantId='{}' name='{}' → group='{}'",
                tenantId, name, closedGroupId);
        return updated;
    }

    /** Lists RUNNING projects whose {@code podIp} matches — for startup reclaim. */
    public List<ProjectDocument> findRunningByPod(String podIp) {
        Query query = new Query(Criteria.where(F_STATUS).is(ProjectStatus.RUNNING)
                .and(F_POD_IP).is(podIp));
        return mongoTemplate.find(query, ProjectDocument.class);
    }

    public static class ProjectAlreadyExistsException extends RuntimeException {
        public ProjectAlreadyExistsException(String message) {
            super(message);
        }
    }

    public static class ProjectNotFoundException extends RuntimeException {
        public ProjectNotFoundException(String message) {
            super(message);
        }
    }

    public static class ProjectClosedException extends RuntimeException {
        public ProjectClosedException(String message) {
            super(message);
        }
    }

    public static class ProjectStatusConflictException extends RuntimeException {
        public ProjectStatusConflictException(String message) {
            super(message);
        }
    }

    public static class SystemProjectProtectedException extends RuntimeException {
        public SystemProjectProtectedException(String message) {
            super(message);
        }
    }

    public static class ReservedProjectNameException extends RuntimeException {
        public ReservedProjectNameException(String message) {
            super(message);
        }
    }
}
