package de.mhus.vance.shared.project;

import de.mhus.vance.shared.audit.AuditService;
import java.time.Duration;
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
                .set(F_CLAIMED_AT, now);
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
        Query query = new Query(Criteria.where(F_HOME_POD).is(selfPodId));
        Update update = new Update()
                .unset(F_HOME_POD)
                .unset(F_HOME_NODE)
                .unset(F_CLAIMED_AT);
        long modified = mongoTemplate.updateMulti(query, update, ProjectDocument.class)
                .getModifiedCount();
        for (ProjectDocument project : held) {
            megadodoService.projectHomeReleased(
                    project.getTenantId(), project.getName(),
                    selfNodeName, selfPodId, selfAddress);
        }
        return modified;
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
                .limit(Math.max(1, limit));
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
