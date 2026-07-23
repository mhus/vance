package de.mhus.vance.shared.project;

import de.mhus.vance.shared.audit.AuditService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Returns {@code true} when {@code projectName} refers to a system
     * project that intentionally has no Home Pod — currently every
     * project whose name starts with {@link #SYSTEM_NAME_PREFIX}
     * ({@code _vance}, {@code _user_<login>}). These projects live
     * pod-locally on whichever brain process the user's WS lands on
     * and are never claimed via {@code homeNode}; lifecycle and
     * pod-claim paths short-circuit on this check.
     *
     * <p>Rationale and consequences are spelled out in
     * {@code specification/eddie-engine.md} §2.4.
     */
    public static boolean isPodless(String projectName) {
        return projectName != null && projectName.startsWith(SYSTEM_NAME_PREFIX);
    }

    /** Field names — kept here so atomic queries don't drift. */
    private static final String F_TENANT = "tenantId";
    private static final String F_NAME = "name";
    private static final String F_STATUS = "status";
    private static final String F_HOME_CLUSTER = "homeNode";
    private static final String F_CLAIMED_AT = "claimedAt";
    private static final String F_LIFECYCLE_TYPE = "lifecycleType";
    private static final String F_HOME_RESOURCE_SCORE = "homeResourceScore";

    private final ProjectRepository repository;
    private final MongoTemplate mongoTemplate;
    private final AuditService auditService;
    /** Lazy — ProjectService is a core bean; resolve the permission layer
     *  on demand to keep it out of this service's construction graph. */
    private final org.springframework.beans.factory.ObjectProvider<
            de.mhus.vance.shared.permission.PermissionService> permissionServiceProvider;

    public Optional<ProjectDocument> findByTenantAndName(String tenantId, String name) {
        return repository.findByTenantIdAndName(tenantId, name);
    }

    public boolean existsByTenantAndName(String tenantId, String name) {
        return repository.existsByTenantIdAndName(tenantId, name);
    }

    public List<ProjectDocument> all(String tenantId) {
        return repository.findByTenantId(tenantId);
    }

    /**
     * The projects in {@code tenantId} that {@code subject} is allowed to
     * READ — the authorized list surface. Authorization is a hard check
     * owned here, at the data source, <em>not</em> re-implemented by each
     * frontend: REST, WebSocket and the LLM tools all list through this
     * method, so project visibility is decided in exactly one place and
     * cannot drift or be forgotten on a surface. {@link #all(String)}
     * stays unfiltered for SYSTEM / internal callers (model discovery,
     * migration, bootstrap). (permission-system finding #11)
     */
    public List<ProjectDocument> listReadableBy(
            String tenantId, de.mhus.vance.shared.permission.SecurityContext subject) {
        return filterReadable(tenantId, subject, all(tenantId));
    }

    /**
     * Keep only the projects {@code subject} may READ. The check primitive
     * that {@link #listReadableBy} and group/filtered list surfaces share,
     * so the READ decision lives once in this service rather than in each
     * caller. (permission-system finding #11)
     */
    public List<ProjectDocument> filterReadable(
            String tenantId, de.mhus.vance.shared.permission.SecurityContext subject,
            List<ProjectDocument> projects) {
        de.mhus.vance.shared.permission.PermissionService permissionService =
                permissionServiceProvider.getObject();
        return projects.stream()
                .filter(p -> permissionService.check(
                        subject,
                        new de.mhus.vance.shared.permission.Resource.Project(tenantId, p.getName()),
                        de.mhus.vance.shared.permission.Action.READ))
                .toList();
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
        LifecycleType lifecycleType = (kind == ProjectKind.SYSTEM)
                ? LifecycleType.HOMELESS
                : LifecycleType.EPHEMERAL;
        ProjectDocument project = ProjectDocument.builder()
                .tenantId(tenantId)
                .name(name)
                .title(title)
                .projectGroupId(projectGroupId)
                .teamIds(teamIds == null ? new ArrayList<>() : new ArrayList<>(teamIds))
                .enabled(true)
                .kind(kind)
                .lifecycleType(lifecycleType)
                .build();
        ProjectDocument saved = repository.save(project);
        log.info("Created project tenantId='{}' name='{}' kind={} lifecycle={} id='{}'",
                saved.getTenantId(), saved.getName(), saved.getKind(),
                saved.getLifecycleType(), saved.getId());
        auditService.projectCreate(tenantId, name);
        return saved;
    }

    /**
     * Atomically claims {@code (tenantId, name)} for {@code selfCluster}: the
     * CAS predicate accepts the claim when {@code homeNode} is currently
     * {@code null}, equal to {@code selfCluster}, or pointing at a cluster
     * node that is not in {@code liveClusters} (stale-takeover). Refreshes
     * {@code homeNode} and {@code claimedAt}; lifecycle status is left
     * untouched.
     *
     * <p>Returns {@link Optional#empty()} when the claim was rejected — that
     * means another live pod holds the project; the caller must redirect.
     * Throws {@link ProjectNotFoundException} when the document does not
     * exist at all, and {@link ProjectClosedException} when it is CLOSED.
     *
     * <p>{@code liveClusters} is a snapshot of the {@code nodeName}s of
     * non-stale pods in the same cluster, as seen by the caller. It must
     * contain {@code selfCluster}; the caller (which lives in
     * {@code vance-brain}) builds it from {@code BrainPodService}.
     */
    public Optional<ProjectDocument> claim(
            String tenantId,
            String name,
            String selfCluster,
            Set<String> liveClusters) {
        if (isPodless(name)) {
            throw new IllegalArgumentException(
                    "Project '" + name + "' is podless — refusing to set homeNode. "
                            + "Use ProjectManagerService.claimForLocalPod() which "
                            + "short-circuits on isPodless().");
        }
        ProjectDocument current = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new ProjectNotFoundException(
                        "Project '" + name + "' not found in tenant '" + tenantId + "'"));
        if (current.getStatus() == ProjectStatus.CLOSED) {
            throw new ProjectClosedException(
                    "Project '" + name + "' is CLOSED — cannot claim");
        }
        Criteria base = Criteria.where(F_TENANT).is(tenantId).and(F_NAME).is(name);
        Criteria casPredicate = new Criteria().orOperator(
                Criteria.where(F_HOME_CLUSTER).is(null),
                Criteria.where(F_HOME_CLUSTER).is(selfCluster),
                Criteria.where(F_HOME_CLUSTER).nin(liveClusters));
        Query query = new Query(new Criteria().andOperator(base, casPredicate));
        Update update = new Update()
                .set(F_HOME_CLUSTER, selfCluster)
                .set(F_CLAIMED_AT, Instant.now());
        ProjectDocument updated = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                ProjectDocument.class);
        if (updated == null) {
            // The base (tenantId, name) match exists (we just read it above)
            // — so a null here means the CAS predicate failed. Another live
            // cluster holds the claim. Caller decides whether to redirect.
            log.debug("Project '{}/{}' claim rejected: holder='{}', selfCluster='{}'",
                    tenantId, name, current.getHomeNode(), selfCluster);
            return Optional.empty();
        }
        if (!Objects.equals(current.getHomeNode(), selfCluster)) {
            log.info("Project '{}' claimed by cluster '{}' (was '{}', status={})",
                    name, selfCluster, current.getHomeNode(), current.getStatus());
        }
        return Optional.of(updated);
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
        auditService.projectClose(tenantId, name, closedGroupId);
        return updated;
    }

    /** Lists RUNNING projects owned by {@code homeNode} — for startup reclaim. */
    public List<ProjectDocument> findRunningByHomeNode(String homeNode) {
        Query query = new Query(Criteria.where(F_STATUS).is(ProjectStatus.RUNNING)
                .and(F_HOME_CLUSTER).is(homeNode));
        return mongoTemplate.find(query, ProjectDocument.class);
    }

    /**
     * Lists podless projects (system + per-user) that are not in a
     * terminal state. Podless projects never reach {@code RUNNING}
     * because {@code bringPodless()} leaves the status untouched — so
     * pod-scoped sweepers (auto-summary, indexers) cannot rely on the
     * regular {@link #findRunningByHomeNode} filter to see them.
     * They live on whichever pod the user's WS lands on; any pod may
     * sweep their documents because per-doc work is gated by an atomic
     * claim.
     */
    public List<ProjectDocument> findPodlessActive() {
        Query query = new Query(Criteria.where(F_NAME).regex("^" + SYSTEM_NAME_PREFIX)
                .and(F_STATUS).ne(ProjectStatus.CLOSED));
        return mongoTemplate.find(query, ProjectDocument.class);
    }

    /**
     * Lists every project owned by {@code homeNode}, regardless of
     * project status. Used by the cluster heartbeat to denormalise
     * "what does this cluster node own right now" into the brain-pod row.
     */
    public List<ProjectDocument> findByHomeNode(String homeNode) {
        Query query = new Query(Criteria.where(F_HOME_CLUSTER).is(homeNode));
        return mongoTemplate.find(query, ProjectDocument.class);
    }

    /**
     * Bulk-clears {@code homeNode} on every project whose current
     * owner is not in {@code liveClusters}. Idempotent — two pods running
     * the same cleanup converge on the same final state. Returns the
     * number of documents actually modified. Used by
     * {@code ProjectStartupReclaimer} at boot.
     *
     * <p>{@code $nin} also matches documents where the field is missing
     * or {@code null}; setting {@code null} on an already-{@code null}
     * field is a no-op in MongoDB and is not counted in
     * {@code modifiedCount}, so the operation stays accurate.
     *
     * <p>A defensive no-op when {@code liveClusters} is empty: that
     * would otherwise match every document and wipe every claim. The
     * caller is supposed to always include this pod's own node name,
     * but we guard here so a misuse can't trigger a cluster-wide reset.
     */
    public long clearStaleHomeNodes(Set<String> liveClusters) {
        if (liveClusters == null || liveClusters.isEmpty()) {
            log.warn("clearStaleHomeNodes called with empty liveClusters — skipping");
            return 0;
        }
        Query query = new Query(Criteria.where(F_HOME_CLUSTER).nin(liveClusters));
        Update update = new Update().set(F_HOME_CLUSTER, null);
        return mongoTemplate.updateMulti(query, update, ProjectDocument.class)
                .getModifiedCount();
    }

    /**
     * Lists PERMANENT projects that have no live owner pod — selector
     * {@code lifecycleType=PERMANENT AND status non-CLOSED AND
     * (homeNode IS NULL OR homeNode NOT IN liveClusters)}. Candidates
     * for the Boot-Self-Pull and the Cluster-Master Distributor (see
     * {@code specification/cluster-project-management.md} §5).
     *
     * <p>Pass {@code liveClusters} to filter out projects whose
     * {@code homeNode} still points at a stale node-name — pods on boot
     * have not yet wiped those via {@link #clearStaleHomeNodes}. The
     * empty set is treated as "homeNode null only" (defensive).
     */
    public List<ProjectDocument> findPermanentOrphans(Set<String> liveClusters, int limit) {
        Criteria typeAndStatus = Criteria.where(F_LIFECYCLE_TYPE).is(LifecycleType.PERMANENT)
                .and(F_STATUS).ne(ProjectStatus.CLOSED);
        Criteria orphan = (liveClusters == null || liveClusters.isEmpty())
                ? Criteria.where(F_HOME_CLUSTER).is(null)
                : new Criteria().orOperator(
                        Criteria.where(F_HOME_CLUSTER).is(null),
                        Criteria.where(F_HOME_CLUSTER).nin(liveClusters));
        Query query = new Query(new Criteria().andOperator(typeAndStatus, orphan))
                .limit(Math.max(1, limit));
        return mongoTemplate.find(query, ProjectDocument.class);
    }

    /**
     * Sum of {@code homeResourceScore} over every non-CLOSED project
     * currently owned by {@code homeNode}. Used by the pod heartbeat to
     * refresh {@code BrainPodDocument.resourcesCurrentScore} and by the
     * Distributor to project pod load while planning a distribution
     * round.
     */
    public int sumScoreByHomeNode(String homeNode) {
        if (homeNode == null || homeNode.isBlank()) return 0;
        Query query = new Query(Criteria.where(F_HOME_CLUSTER).is(homeNode)
                .and(F_STATUS).ne(ProjectStatus.CLOSED));
        int total = 0;
        for (ProjectDocument p : mongoTemplate.find(query, ProjectDocument.class)) {
            total += Math.max(1, p.getHomeResourceScore());
        }
        return total;
    }

    /**
     * Atomically switches {@code lifecycleType} between
     * {@link LifecycleType#EPHEMERAL} and {@link LifecycleType#PERMANENT}.
     * Refuses {@link LifecycleType#HOMELESS} (immutable per SYSTEM-kind)
     * and refuses to mutate SYSTEM projects.
     */
    public ProjectDocument setLifecycleType(String tenantId, String name, LifecycleType value) {
        if (value == LifecycleType.HOMELESS) {
            throw new IllegalArgumentException(
                    "Cannot set lifecycleType=HOMELESS — that is reserved for SYSTEM projects");
        }
        ProjectDocument current = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new ProjectNotFoundException(
                        "Project '" + name + "' not found in tenant '" + tenantId + "'"));
        if (current.getKind() == ProjectKind.SYSTEM) {
            throw new SystemProjectProtectedException(
                    "Project '" + name + "' is SYSTEM — lifecycleType is HOMELESS and immutable");
        }
        Query query = new Query(Criteria.where(F_TENANT).is(tenantId).and(F_NAME).is(name));
        Update update = new Update().set(F_LIFECYCLE_TYPE, value);
        ProjectDocument updated = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                ProjectDocument.class);
        if (updated == null) {
            throw new ProjectNotFoundException(
                    "Project '" + name + "' disappeared during setLifecycleType");
        }
        log.info("Project '{}/{}' lifecycleType {} → {}",
                tenantId, name, current.getLifecycleType(), value);
        return updated;
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
