package de.mhus.vance.shared.project;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.cluster.PodSelector;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private static final String F_HOME_POD = "homePodId";
    private static final String F_HOME_NODE = "homeNode";
    private static final String F_CLAIMED_AT = "claimedAt";
    private static final String F_LIFECYCLE_TYPE = "lifecycleType";
    private static final String F_OWNER_REQUIRED = "ownerRequired";
    private static final String F_HOME_RESOURCE_SCORE = "homeResourceScore";
    private static final String F_PLACEMENT_SELECTOR = "placementSelector";
    private static final String F_PENDING_SINCE = "pendingSince";

    /**
     * The statuses that express the intent "should be live somewhere" — the
     * only ones an owner is claimed for. {@code SUSPENDING} and
     * {@code SUSPENDED} express the opposite intent and {@code CLOSED} is
     * terminal; recovery must not overrule any of the three.
     */
    private static final List<ProjectStatus> WANTS_TO_RUN = List.of(
            ProjectStatus.INIT, ProjectStatus.RECOVERING, ProjectStatus.RUNNING);

    /**
     * The same "should be live somewhere" question as {@link #WANTS_TO_RUN},
     * asked about a single project instead of as a query predicate.
     *
     * <p>Exposed because the recovery selector is not the only caller that has
     * to respect a suspend. {@code ProjectLifecycleService.bring} deliberately
     * transitions <em>any</em> non-RUNNING status to RUNNING — that is right
     * for an explicit bring and wrong for anything that happens by itself, so
     * an implicit caller (a workspace read adopting an unowned project, say)
     * has to ask first. One authority for the set, two shapes of question.
     */
    public static boolean wantsToRun(@Nullable ProjectStatus status) {
        return status != null && WANTS_TO_RUN.contains(status);
    }

    private final ProjectRepository repository;
    private final MongoTemplate mongoTemplate;
    private final AuditService auditService;
    private final de.mhus.vance.shared.megadodo.MegadodoService megadodoService;
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
                : LifecycleType.AUTO;
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
        megadodoService.projectCreated(tenantId, name, /*actor*/ null);
        return saved;
    }

    /**
     * Atomically takes the ownership lease on {@code (tenantId, name)} for
     * {@code selfPodId}. The CAS predicate accepts when the lease is
     * unclaimed, already ours, or <b>expired</b> — a holder that stopped
     * renewing within {@code leaseTtl} is gone by definition. Refreshes
     * {@code homePodId}, {@code homeNode} and {@code claimedAt}; lifecycle
     * status is left untouched.
     *
     * <p>The predicate is entirely local to the document: no live-pod
     * snapshot has to be assembled and passed in, which is what previously
     * forced every caller to build a {@code liveClusters} set (and to guard
     * against the empty one wiping every claim in the cluster).
     *
     * <p>Returns {@link Optional#empty()} when the claim was rejected — that
     * means another pod holds a valid lease; the caller must redirect.
     * Throws {@link ProjectNotFoundException} when the document does not
     * exist at all, and {@link ProjectClosedException} when it is CLOSED.
     */
    public Optional<ProjectDocument> claim(
            String tenantId,
            String name,
            String selfPodId,
            String selfNodeName,
            String selfAddress,
            Duration leaseTtl) {
        if (isPodless(name)) {
            throw new IllegalArgumentException(
                    "Project '" + name + "' is podless — refusing to take a lease. "
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
        Instant now = Instant.now();
        Criteria base = Criteria.where(F_TENANT).is(tenantId).and(F_NAME).is(name);
        Criteria casPredicate = new Criteria().orOperator(
                Criteria.where(F_HOME_POD).is(null),
                Criteria.where(F_HOME_POD).is(selfPodId),
                expiredLease(now, leaseTtl));
        Query query = new Query(new Criteria().andOperator(base, casPredicate));
        Update update = new Update()
                .set(F_HOME_POD, selfPodId)
                .set(F_HOME_NODE, selfNodeName)
                .set(F_CLAIMED_AT, now)
                // The claim is the moment a project stops waiting for a pod, so
                // it is the one place that can clear the mark without a second
                // write path deciding when "placed" happened.
                .unset(F_PENDING_SINCE);
        ProjectDocument updated = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                ProjectDocument.class);
        if (updated == null) {
            // The base (tenantId, name) match exists (we just read it above)
            // — so a null here means the CAS predicate failed. Another pod
            // holds a live lease. Caller decides whether to redirect.
            log.debug("Project '{}/{}' claim rejected: holder='{}' ({}), self='{}'",
                    tenantId, name, current.getHomeNode(), current.getHomePodId(), selfPodId);
            return Optional.empty();
        }
        if (!Objects.equals(current.getHomePodId(), selfPodId)) {
            log.info("Project '{}' leased by pod '{}' (was '{}', status={})",
                    name, selfNodeName, current.getHomeNode(), current.getStatus());
            // Only on the transition. Claiming is idempotent and doubles as a
            // lease refresh, so a row per call would be a row every time
            // anybody touched the project.
            megadodoService.projectHomeClaimed(
                    tenantId, name, selfNodeName, selfPodId, selfAddress,
                    current.getHomeNode(), current.getClaimedAt());
        }
        return Optional.of(updated);
    }

    /**
     * "The lease is not being renewed any more" as a Mongo predicate. A
     * missing {@code claimedAt} counts as expired for the same reason
     * {@code ProjectOwnership.isExpired} says so: a holder without a renewal
     * timestamp cannot be validated, and treating it as valid would strand
     * the project forever.
     */
    private static Criteria expiredLease(Instant now, Duration leaseTtl) {
        Instant cutoff = now.minus(leaseTtl);
        return new Criteria().orOperator(
                Criteria.where(F_CLAIMED_AT).is(null),
                Criteria.where(F_CLAIMED_AT).lt(cutoff));
    }

    /**
     * Renews every lease this pod holds in a single operation, and reports
     * how many it still holds.
     *
     * <p>One {@code updateMulti} per beat, whatever the number of tenants and
     * projects — that is the whole point of keying ownership on a pod id with
     * an index behind it. Renewing per project would put the heartbeat cost on
     * a curve nobody wants to be on in a large installation.
     *
     * <p>The return value is the <em>matched</em> count, not the modified one:
     * the question is "how many do I still hold", and a match answers it
     * whether or not the timestamp happened to change. Compared against what
     * the pod thinks it activated, a shortfall means a lease was taken away —
     * see {@code ProjectLeaseService}.
     */
    public long renewLeases(String selfPodId, Instant now) {
        if (selfPodId == null || selfPodId.isBlank()) return 0;
        Query query = new Query(Criteria.where(F_HOME_POD).is(selfPodId));
        Update update = new Update().set(F_CLAIMED_AT, now);
        return mongoTemplate.updateMulti(query, update, ProjectDocument.class)
                .getMatchedCount();
    }

    /**
     * Drops every lease this pod holds — the clean-shutdown courtesy that
     * lets the next pod take over immediately instead of waiting out the TTL.
     *
     * <p>Best-effort by design: correctness does not depend on it, because an
     * un-renewed lease expires on its own. That is the difference from the
     * previous model, where a missed cleanup left a claim that blocked
     * takeover until some pod happened to boot.
     */
    public long releaseLeases(String selfPodId, String selfNodeName, String selfAddress) {
        if (selfPodId == null || selfPodId.isBlank()) return 0;
        // Read before the write, so the feed can name the projects. One extra
        // query on a shutdown path, and the only chance to record a departure
        // at all: an expiring lease has nobody left to write a row.
        List<ProjectDocument> held = findByHomePodId(selfPodId);
        long released = 0;
        for (ProjectDocument project : held) {
            // One guarded write per project rather than a single updateMulti.
            // The batch was cheaper but could not say *which* projects it
            // freed, so the feed announced a release for everything in the
            // pre-read list — including a project another pod had claimed in
            // the meantime, because this pod's lease had already expired. To an
            // operator that row reads as "no home" for a project that is in
            // fact healthy elsewhere, which is the opposite of what the feed is
            // for. The batching is what changes, not the cost that matters:
            // this runs once per shutdown and already wrote a feed row per
            // project.
            Query query = new Query(Criteria.where(F_TENANT).is(project.getTenantId())
                    .and(F_NAME).is(project.getName())
                    .and(F_HOME_POD).is(selfPodId));
            Update update = new Update()
                    .unset(F_HOME_POD)
                    .unset(F_HOME_NODE)
                    .unset(F_CLAIMED_AT);
            if (mongoTemplate.updateFirst(query, update, ProjectDocument.class)
                    .getModifiedCount() == 0) {
                // Taken over between the read and here — not ours to release,
                // and not ours to report.
                continue;
            }
            released++;
            megadodoService.projectHomeReleased(
                    project.getTenantId(), project.getName(),
                    selfNodeName, selfPodId, selfAddress);
        }
        return released;
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
     * Renames a project — {@code name} is the business key, so this moves the
     * identity every other entity points at.
     *
     * <p><b>Only ever call this through {@code ProjectMaintenanceService}.</b>
     * On its own it does exactly half the job: the document says the new name
     * and every session, document, setting and grant still says the old one.
     * The service that owns the whole move calls this last, after the handlers
     * have carried their references over.
     *
     * <p>Refuses {@link ProjectKind#SYSTEM} projects: {@code _vance} and the
     * per-user hubs are addressed by name from code and from settings, and a
     * renamed hub is a hub nobody finds.
     *
     * @throws ProjectNotFoundException if the project does not exist
     * @throws SystemProjectProtectedException if the project is SYSTEM
     * @throws ReservedProjectNameException if {@code newName} takes the
     *     reserved prefix
     * @throws ProjectAlreadyExistsException if {@code newName} is taken
     */
    public ProjectDocument rename(String tenantId, String name, String newName) {
        ProjectDocument current = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new ProjectNotFoundException(
                        "Project '" + name + "' not found in tenant '" + tenantId + "'"));
        if (current.getKind() == ProjectKind.SYSTEM) {
            throw new SystemProjectProtectedException(
                    "Project '" + name + "' is SYSTEM — cannot rename");
        }
        if (newName.startsWith(SYSTEM_NAME_PREFIX)) {
            throw new ReservedProjectNameException(
                    "Project name '" + newName + "' starts with the reserved '"
                            + SYSTEM_NAME_PREFIX + "' prefix");
        }
        requirePathSafeName(newName);
        if (repository.existsByTenantIdAndName(tenantId, newName)) {
            throw new ProjectAlreadyExistsException(
                    "Project '" + newName + "' already exists in tenant '" + tenantId + "'");
        }
        Query query = new Query(Criteria.where(F_TENANT).is(tenantId).and(F_NAME).is(name));
        Update update = new Update().set(F_NAME, newName);
        ProjectDocument updated = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                ProjectDocument.class);
        if (updated == null) {
            throw new ProjectNotFoundException(
                    "Project '" + name + "' disappeared during rename");
        }
        log.info("Renamed project tenantId='{}' '{}' → '{}'", tenantId, name, newName);
        auditService.projectRename(tenantId, name, newName);
        megadodoService.projectRenamed(tenantId, name, newName, /*actor*/ null);
        return updated;
    }

    /**
     * Removes the project document itself. The counterpart to {@link #create},
     * and the last step of a hard delete — <b>not</b> a lifecycle transition:
     * {@link #close} is what ends a project's life, this is what ends its
     * existence.
     *
     * <p><b>Only ever call this through {@code ProjectMaintenanceService}.</b>
     * The document is the index back to everything the project owns; removing
     * it while that data is still there strands the data with no way left to
     * address it.
     *
     * <p>Idempotent — a project that is already gone returns {@code false}.
     *
     * @throws SystemProjectProtectedException if the project is SYSTEM
     */
    public boolean delete(String tenantId, String name) {
        Optional<ProjectDocument> found = repository.findByTenantIdAndName(tenantId, name);
        if (found.isEmpty()) {
            return false;
        }
        if (found.get().getKind() == ProjectKind.SYSTEM) {
            throw new SystemProjectProtectedException(
                    "Project '" + name + "' is SYSTEM — cannot delete");
        }
        repository.delete(found.get());
        log.info("Deleted project tenantId='{}' name='{}'", tenantId, name);
        auditService.projectDelete(tenantId, name);
        megadodoService.projectDeleted(tenantId, name, /*actor*/ null);
        return true;
    }

    // ─── Per-user hub projects ─────────────────────────────────────────────
    //
    // The one kind of SYSTEM project that legitimately ends: `_user_<login>`
    // belongs to an account, and when the account goes or is renamed, so is
    // the hub — its name encodes the login, so leaving it behind produces a
    // project nobody can address again.
    //
    // Two separate methods rather than an `allowSystem` flag on the ordinary
    // ones. A flag is a trapdoor that reaches `_vance` as easily as a hub; a
    // method that refuses anything not named `_user_<something>` cannot.

    /**
     * Whether {@code name} is a per-user hub — {@code _user_} plus at least one
     * character. The guard on both hub methods, and the reason neither can
     * touch {@code _vance}.
     */
    public static boolean isUserHub(String name) {
        return name.startsWith(HUB_PROJECT_NAME_PREFIX)
                && name.length() > HUB_PROJECT_NAME_PREFIX.length();
    }

    /**
     * Name prefix of the per-user hub projects. Mirrors
     * {@code HomeBootstrapService.HUB_PROJECT_NAME_PREFIX} — a compile-time
     * constant, so this does not make the project package depend on the home
     * package. Two literals of one string is a risk; both are covered by
     * {@code HomeBootstrapServiceTest} and the hub handler's test.
     */
    public static final String HUB_PROJECT_NAME_PREFIX = "_user_";

    /**
     * Removes a per-user hub project document.
     *
     * <p>Only ever call this through {@code ProjectMaintenanceService}, and
     * only as part of deleting the account it belongs to.
     *
     * @throws IllegalArgumentException if {@code name} is not a hub
     */
    public boolean deleteUserHub(String tenantId, String name) {
        requireUserHub(name, "delete");
        Optional<ProjectDocument> found = repository.findByTenantIdAndName(tenantId, name);
        if (found.isEmpty()) {
            return false;
        }
        repository.delete(found.get());
        log.info("Deleted user-hub project tenantId='{}' name='{}'", tenantId, name);
        auditService.projectDelete(tenantId, name);
        megadodoService.projectDeleted(tenantId, name, /*actor*/ null);
        return true;
    }

    /**
     * Renames a per-user hub project — both names must be hubs, because the
     * hub of a renamed account is still a hub.
     *
     * @throws IllegalArgumentException if either name is not a hub
     * @throws ProjectAlreadyExistsException if the target name is taken
     */
    public ProjectDocument renameUserHub(String tenantId, String name, String newName) {
        requireUserHub(name, "rename");
        requireUserHub(newName, "rename to");
        if (repository.existsByTenantIdAndName(tenantId, newName)) {
            throw new ProjectAlreadyExistsException(
                    "Project '" + newName + "' already exists in tenant '" + tenantId + "'");
        }
        Query query = new Query(Criteria.where(F_TENANT).is(tenantId).and(F_NAME).is(name));
        Update update = new Update().set(F_NAME, newName);
        ProjectDocument updated = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                ProjectDocument.class);
        if (updated == null) {
            throw new ProjectNotFoundException(
                    "Hub project '" + name + "' not found in tenant '" + tenantId + "'");
        }
        log.info("Renamed user-hub project tenantId='{}' '{}' → '{}'", tenantId, name, newName);
        auditService.projectRename(tenantId, name, newName);
        megadodoService.projectRenamed(tenantId, name, newName, /*actor*/ null);
        return updated;
    }

    private static void requireUserHub(String name, String operation) {
        if (!isUserHub(name)) {
            throw new IllegalArgumentException(
                    "Refusing to " + operation + " '" + name + "' as a user hub — only projects"
                            + " named '" + HUB_PROJECT_NAME_PREFIX + "<login>' qualify");
        }
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
        megadodoService.projectClosed(tenantId, name, /*actor*/ null);
        return updated;
    }

    /**
     * Lists RUNNING projects leased by {@code selfPodId} — the pod-local
     * sweeper selector (RAG index, auto-summary, Trillian heartbeat).
     *
     * <p>Only ever called with the caller's <em>own</em> pod id, which is why
     * it needs no lease-expiry check: a pod asking what it holds is asking
     * about a lease it is renewing itself. Do not repurpose this to look at
     * another pod's projects — use {@code ProjectOwnership} for that.
     */
    public List<ProjectDocument> findRunningByHomePodId(String selfPodId) {
        Query query = new Query(Criteria.where(F_STATUS).is(ProjectStatus.RUNNING)
                .and(F_HOME_POD).is(selfPodId));
        return mongoTemplate.find(query, ProjectDocument.class);
    }

    /**
     * Lists podless projects (system + per-user) that are not in a
     * terminal state. Podless projects never reach {@code RUNNING}
     * because {@code bringPodless()} leaves the status untouched — so
     * pod-scoped sweepers (auto-summary, indexers) cannot rely on the
     * regular {@link #findRunningByHomePodId} filter to see them.
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
     * Lists every project leased by {@code selfPodId}, regardless of project
     * status. Used by the cluster heartbeat to denormalise "what does this pod
     * hold right now" into the brain-pod row. Own-pod only, see
     * {@link #findRunningByHomePodId}.
     */
    public List<ProjectDocument> findByHomePodId(String selfPodId) {
        Query query = new Query(Criteria.where(F_HOME_POD).is(selfPodId));
        return mongoTemplate.find(query, ProjectDocument.class);
    }

    /**
     * Lists projects that need an owner pod but hold no valid lease — the
     * candidate set for the Boot-Self-Pull and the Cluster-Master Distributor
     * (see {@code specification/cluster-project-management.md} §5).
     *
     * <p>"Needs an owner" is the operator override where one was set, and the
     * derived {@code ownerRequired} otherwise:
     * <ul>
     *   <li>{@link LifecycleType#PERMANENT} — always.</li>
     *   <li>{@link LifecycleType#AUTO} — when {@code ownerRequired} is true,
     *       i.e. the project carries scheduler entries or hooks.</li>
     *   <li>{@link LifecycleType#EPHEMERAL} — never (explicit opt-out).</li>
     *   <li>{@link LifecycleType#HOMELESS} — never (no pod affinity at all).</li>
     * </ul>
     *
     * <p><b>Status is read as intent, and only two intents want an owner.</b>
     * {@code WANTS_TO_RUN} is {@code INIT}, {@code RECOVERING},
     * {@code RUNNING} — "should be live somewhere". The selector used to be
     * {@code status != CLOSED}, which swept {@code SUSPENDED} and
     * {@code SUSPENDING} in with them, and {@code ProjectLifecycleService.bring}
     * transitions <em>any</em> non-RUNNING status straight to RUNNING. Suspend
     * therefore did not survive a restart: an operator suspends a project
     * because its nightly scheduler costs money, the holder's lease expires on
     * the next reboot, and the Boot-Self-Pull brings it back — for the very
     * scheduler that was the reason to suspend it. Getting back is still
     * possible, just not by itself: an explicit locate or bring does it.
     *
     * <p>An indexable range scan: both "needs an owner" and "stranded" are
     * properties of the document, where the predecessor had to {@code $nin} a
     * live-pod list that grew with the cluster.
     *
     * <p>Replaces {@code findPermanentOrphans}, which selected on PERMANENT
     * alone and therefore matched nothing at all — nothing in the tree ever
     * assigned that value ({@code planning/project-ownership-lease-design.md}
     * §1.1).
     */
    public List<ProjectDocument> findProjectsNeedingOwner(Duration leaseTtl, int limit) {
        return findProjectsNeedingOwner(leaseTtl, limit, 0);
    }

    /**
     * Paged variant. {@code skip} exists because a caller may reject candidates
     * for reasons this query cannot express — the Boot-Self-Pull filters on
     * whether the local pod is <em>eligible</em> for the project, and eligibility
     * is a map comparison with unknown keys, not a Mongo predicate.
     *
     * <p>That turns {@code limit} from a bound on work into a page size: a page
     * of candidates this pod cannot take must not be read as "nothing left to
     * do", or eligible projects sitting behind it are never pulled and wait for
     * the distributor — which does not exist when the master role is disabled.
     *
     * <p>{@code skip} over a shrinking set is imprecise on purpose: a project
     * placed during the walk leaves the result set and shifts the rest forward,
     * so a page boundary can step over one. Bounded and self-correcting — the
     * next round or the distributor picks it up — and much cheaper than a
     * stable cursor over a set that is defined by "nobody owns this right now".
     */
    public List<ProjectDocument> findProjectsNeedingOwner(
            Duration leaseTtl, int limit, int skip) {
        Criteria needsOwner = new Criteria().orOperator(
                Criteria.where(F_LIFECYCLE_TYPE).is(LifecycleType.PERMANENT),
                Criteria.where(F_LIFECYCLE_TYPE).is(LifecycleType.AUTO)
                        .and(F_OWNER_REQUIRED).is(true));
        Criteria stranded = new Criteria().orOperator(
                Criteria.where(F_HOME_POD).is(null),
                expiredLease(Instant.now(), leaseTtl));
        Query query = new Query(new Criteria().andOperator(
                        Criteria.where(F_STATUS).in(WANTS_TO_RUN),
                        needsOwner,
                        stranded))
                .limit(Math.max(1, limit))
                .skip(Math.max(0, skip));
        return mongoTemplate.find(query, ProjectDocument.class);
    }

    /**
     * Every project currently marked as needing an owner pod. Small by
     * construction and index-backed — it is the re-derivation candidate set,
     * not a scan.
     */
    public List<ProjectDocument> findOwnerRequired() {
        return mongoTemplate.find(
                new Query(Criteria.where(F_OWNER_REQUIRED).is(true)), ProjectDocument.class);
    }

    /**
     * Sets the derived {@code ownerRequired} flag, returning whether the value
     * actually changed. Called by {@code ProjectOwnerRequirementService} after
     * a document under one of the activation-source prefixes changed.
     *
     * <p>Conditional write: the filter includes the negation of the target
     * value, so a recompute that confirms the status quo costs a matched-zero
     * update and no disk write. In steady state — which is almost always —
     * this writes nothing.
     */
    public boolean setOwnerRequired(String tenantId, String name, boolean value) {
        Query query = new Query(Criteria.where(F_TENANT).is(tenantId)
                .and(F_NAME).is(name)
                .and(F_OWNER_REQUIRED).ne(value));
        Update update = new Update().set(F_OWNER_REQUIRED, value);
        return mongoTemplate.updateFirst(query, update, ProjectDocument.class)
                .getModifiedCount() > 0;
    }

    /**
     * Sum of {@code homeResourceScore} over every non-CLOSED project leased by
     * {@code selfPodId}. Used by the pod heartbeat to refresh
     * {@code BrainPodDocument.resourcesCurrentScore} and by the Distributor to
     * project pod load while planning a distribution round.
     */
    public int sumScoreByHomePodId(String selfPodId) {
        if (selfPodId == null || selfPodId.isBlank()) return 0;
        Query query = new Query(Criteria.where(F_HOME_POD).is(selfPodId)
                .and(F_STATUS).ne(ProjectStatus.CLOSED));
        int total = 0;
        for (ProjectDocument p : mongoTemplate.find(query, ProjectDocument.class)) {
            total += Math.max(1, p.getHomeResourceScore());
        }
        return total;
    }

    /**
     * Atomically switches {@code lifecycleType} between {@link
     * LifecycleType#AUTO}, {@link LifecycleType#EPHEMERAL} and {@link
     * LifecycleType#PERMANENT}. Refuses {@link LifecycleType#HOMELESS}
     * (immutable per SYSTEM-kind) and refuses to mutate SYSTEM projects.
     *
     * <p>This is the operator override over the derived
     * {@code ownerRequired}; {@code AUTO} hands the decision back to it.
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

    /**
     * Drops the lease on <em>one</em> project, guarded on this pod still being
     * the holder. The single-project counterpart to {@link #releaseLeases} —
     * that one is the shutdown sweep, this one is the deliberate hand-over of a
     * project a pod keeps running for.
     *
     * <p>The guard is the whole point: releasing a lease we no longer hold would
     * strip the new owner's claim, and the loser of that race is a project
     * running on a pod that does not own it.
     *
     * <p>{@code pendingSince} is <b>not</b> set here. Whether the project now
     * counts as waiting for a pod is a question for the placement layer, which
     * asks it from the live pod list; writing it here would claim we know the
     * answer before anyone looked.
     *
     * @return {@code true} when the lease was ours and is now gone
     */
    public boolean releaseLease(
            String tenantId, String name,
            String selfPodId, String selfNodeName, String selfAddress) {
        if (selfPodId == null || selfPodId.isBlank()) return false;
        Query query = new Query(Criteria.where(F_TENANT).is(tenantId)
                .and(F_NAME).is(name)
                .and(F_HOME_POD).is(selfPodId));
        Update update = new Update()
                .unset(F_HOME_POD)
                .unset(F_HOME_NODE)
                .unset(F_CLAIMED_AT);
        boolean released = mongoTemplate.updateFirst(query, update, ProjectDocument.class)
                .getModifiedCount() > 0;
        if (released) {
            log.info("Project '{}/{}' lease released by pod '{}'", tenantId, name, selfNodeName);
            megadodoService.projectHomeReleased(
                    tenantId, name, selfNodeName, selfPodId, selfAddress);
        }
        return released;
    }

    /**
     * Marks a project as waiting for a pod, keeping the <em>first</em>
     * timestamp: the guard is what makes {@code oldestSince} in the placement
     * demand mean "waiting since", not "last asked about".
     *
     * <p>A diagnostic field, not a state — no decision reads it. It exists for
     * two things the placement demand cannot derive otherwise: hysteresis (do
     * not provision a pod for a five-second blip) and the projects that are not
     * in the orphan query at all, because nothing about them says they need an
     * owner except that someone just tried to place one
     * ({@code planning/project-placement-labels.md} §6.2).
     *
     * @return {@code true} when this call was the one that set it
     */
    public boolean markPendingPlacement(String tenantId, String name, Instant now) {
        Query query = new Query(Criteria.where(F_TENANT).is(tenantId)
                .and(F_NAME).is(name)
                .and(F_PENDING_SINCE).is(null));
        Update update = new Update().set(F_PENDING_SINCE, now);
        return mongoTemplate.updateFirst(query, update, ProjectDocument.class)
                .getModifiedCount() > 0;
    }

    /**
     * Projects that were last seen unplaceable and are still worth reporting.
     *
     * <p>Two filters beyond "has a {@code pendingSince}":
     * <ul>
     *   <li><b>Age.</b> Past {@code pendingTtl} the mark is ignored rather than
     *       swept. An on-demand project nobody asks for again would otherwise
     *       produce demand forever; "nobody came back" is not demand. No
     *       cleanup tick behind it — the read decides, and the next attempt
     *       rewrites the stamp if the need is still real.</li>
     *   <li><b>Still homeless and still wanting to run.</b> A project that got
     *       placed in the meantime has its mark cleared by the claim, but a
     *       suspend does not clear it, so the status filter has to be here.</li>
     * </ul>
     */
    public List<ProjectDocument> findPendingPlacement(Duration leaseTtl, Duration pendingTtl) {
        Instant now = Instant.now();
        Criteria stranded = new Criteria().orOperator(
                Criteria.where(F_HOME_POD).is(null),
                expiredLease(now, leaseTtl));
        Query query = new Query(new Criteria().andOperator(
                Criteria.where(F_PENDING_SINCE).ne(null).gt(now.minus(pendingTtl)),
                Criteria.where(F_STATUS).in(WANTS_TO_RUN),
                stranded));
        return mongoTemplate.find(query, ProjectDocument.class);
    }

    /**
     * Sets what a project requires of a pod. Both arguments are optional:
     * {@code null} leaves that half unchanged, so an external instance can
     * revise the selector without restating the score.
     *
     * <p>{@code placementSelector} is replaced wholesale, not merged — a
     * control loop reconciling a desired state has to be able to remove a
     * requirement, and merge semantics would need a second verb for that.
     *
     * <p>Takes effect at the next placement. A project already running
     * somewhere stays there; see {@code planning/project-placement-labels.md}
     * §2.4 for why that is the rule and not a gap.
     *
     * @throws PodSelector.InvalidLabelException on a key or value outside the
     *     grammar, so nothing unmatchable reaches persistence
     */
    public ProjectDocument setPlacement(
            String tenantId,
            String name,
            @Nullable Map<String, String> placementSelector,
            @Nullable Integer homeResourceScore) {
        PodSelector.validate(placementSelector);
        if (homeResourceScore != null && homeResourceScore < 0) {
            throw new IllegalArgumentException(
                    "homeResourceScore must not be negative (was " + homeResourceScore + ")");
        }
        ProjectDocument current = repository.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> new ProjectNotFoundException(
                        "Project '" + name + "' not found in tenant '" + tenantId + "'"));
        Update update = new Update();
        if (placementSelector != null) {
            update.set(F_PLACEMENT_SELECTOR, Map.copyOf(placementSelector));
        }
        if (homeResourceScore != null) {
            update.set(F_HOME_RESOURCE_SCORE, homeResourceScore);
        }
        if (update.getUpdateObject().isEmpty()) {
            return current;
        }
        Query query = new Query(Criteria.where(F_TENANT).is(tenantId).and(F_NAME).is(name));
        ProjectDocument updated = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                ProjectDocument.class);
        if (updated == null) {
            throw new ProjectNotFoundException(
                    "Project '" + name + "' disappeared during setPlacement");
        }
        log.info("Project '{}/{}' placement: selector {} → {}, score {} → {}",
                tenantId, name, current.getPlacementSelector(), updated.getPlacementSelector(),
                current.getHomeResourceScore(), updated.getHomeResourceScore());
        return updated;
    }

    /**
     * Names that are safe to be a path segment, because a project name becomes
     * one: the workspace folder is {@code <root>/<tenant>/<project>}.
     *
     * <p>Checked on {@link #rename} and <b>not</b> on {@link #create} — not an
     * oversight. Rename is where an operator types a free string for an
     * existing project, so a {@code ../} there walks the workspace out of its
     * root. Create has the same exposure in principle, but retrofitting the
     * rule would reject names that installations already carry, and breaking
     * existing projects to close a hole nobody has walked through is the worse
     * trade. Tightening create belongs with a migration that can look at what
     * is out there.
     */
    private static final java.util.regex.Pattern PATH_SAFE_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static void requirePathSafeName(String name) {
        if (!PATH_SAFE_NAME.matcher(name).matches() || name.contains("..")) {
            throw new ReservedProjectNameException(
                    "Project name '" + name + "' is not usable as a path segment — letters,"
                            + " digits, '.', '_' and '-' only, starting with a letter or digit,"
                            + " and no '..'. The name becomes a workspace directory.");
        }
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
