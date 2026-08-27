package de.mhus.vance.shared.project.maintenance;

import de.mhus.vance.shared.maintenance.MaintenanceReport;
import de.mhus.vance.shared.maintenance.MaintenanceReport.EntityResult;
import de.mhus.vance.shared.maintenance.MaintenanceReport.Operation;
import de.mhus.vance.shared.maintenance.MaintenanceReport.UnaccountedCollection;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectOwnership;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * The one place that runs a service task across <em>all</em> of a project's
 * data: inspect, delete, rename.
 *
 * <p>It owns no data itself. Every entity is asked through its {@link
 * ProjectDataHandler}, in ascending {@link ProjectDataHandler#order()}, and the
 * list of handlers is whatever the running process has on its classpath — which
 * is how the same code serves the admin shell (only {@code vance-shared} plus
 * addons) and a brain. Handlers sharing an index run in an unspecified order,
 * which is the normal case; the one relation that matters is spelled out on
 * that method.
 *
 * <h2>Two guards, and why they are here rather than in the commands</h2>
 *
 * <p><b>Live lease.</b> A project that a pod currently holds is being worked on
 * — engines running, workspace mounted, sessions open. Deleting or renaming it
 * underneath that pod does not fail loudly, it produces a process operating on
 * data that is no longer there. So both operations refuse while the lease is
 * live, and {@code force} is the operator saying they know the holder is gone.
 * {@link ProjectOwnership} answers the question; nothing here reads
 * {@code homePodId} itself.
 *
 * <p><b>Coverage.</b> A missing handler is invisible by construction: the rows
 * simply stay, and the next project created under that name inherits them. The
 * probe in {@link #unaccountedCollections} looks at what the database actually
 * holds rather than at what the code believes, so a collection added without a
 * handler shows up as a line in every report instead of as a surprise months
 * later. It is reported, never acted on — deleting from a collection nobody
 * claims would mean guessing what its fields mean.
 *
 * <h2>Failure policy</h2>
 *
 * <p>A handler that throws does not stop the run: the remaining entities are
 * still processed and the failure is recorded in its {@link EntityResult#note}.
 * But the project document is only removed when <em>every</em> handler
 * succeeded — it is the index back to the project's data, and dropping it while
 * data is left strands that data with no way to address it. Re-running the same
 * command is the recovery path, which is why every handler must be idempotent.
 *
 * <p>Spec: {@code specification/public/project-maintenance.md}.
 */
@Service
@Slf4j
public class ProjectMaintenanceService {

    /**
     * How many documents of an unclaimed collection are sampled for a
     * {@code projectId} field before the probe decides not to count it.
     *
     * <p>The probe cannot simply count {@code {projectId: X}} in every
     * collection: without an index that is a full scan, and some collections
     * (blob chunks) are enormous. Sampling first keeps the cost proportional to
     * the number of collections rather than to their size. The trade is
     * explicit — a project-scoped collection whose first {@value} rows all
     * happen to omit the field is missed by the probe.
     */
    private static final int PROBE_SAMPLE_SIZE = 20;

    private final List<ProjectDataHandler> handlers;
    private final ProjectService projectService;
    private final MongoTemplate mongoTemplate;
    private final Duration leaseTtl;

    public ProjectMaintenanceService(
            List<ProjectDataHandler> handlers,
            ProjectService projectService,
            MongoTemplate mongoTemplate,
            // Same key the brain's ClusterProperties binds, so a tuned cluster
            // and the admin shell agree on when a lease is dead. ISO-8601 as
            // the literal default: it parses without a conversion service,
            // which a plain @Value cannot count on.
            @Value("${vance.cluster.lease.ttl:PT5M}") Duration leaseTtl) {
        this.handlers = handlers.stream()
                .sorted(Comparator.comparingInt(ProjectDataHandler::order)
                        .thenComparing(ProjectDataHandler::id))
                .toList();
        this.projectService = projectService;
        this.mongoTemplate = mongoTemplate;
        this.leaseTtl = leaseTtl;
    }

    /** The registered handlers, in execution order. */
    public List<ProjectDataHandler> handlers() {
        return handlers;
    }

    // ─── Inspect ───────────────────────────────────────────────────────────

    /**
     * Counts everything the project owns, writing nothing. The dry run for both
     * destructive operations, and on its own the answer to "what is actually in
     * there".
     */
    public MaintenanceReport inspect(String tenantId, String projectId) {
        List<EntityResult> entities = new ArrayList<>();
        for (ProjectDataHandler handler : handlers) {
            entities.add(run(handler, () -> handler.count(tenantId, projectId)));
        }
        return new MaintenanceReport(tenantId, projectId, Operation.INSPECT,
                entities, unaccountedCollections(tenantId, projectId));
    }

    // ─── Delete ────────────────────────────────────────────────────────────

    /**
     * Removes the project and everything it owns. Irreversible.
     *
     * @param force skip the live-lease guard. Never skips the SYSTEM-project
     *     guard — that one is an invariant of the entity, not a precaution.
     * @throws ProjectInUseException if a pod holds a live lease and
     *     {@code force} is false
     * @throws ProjectService.ProjectNotFoundException if the project is gone
     * @throws ProjectService.SystemProjectProtectedException if it is SYSTEM
     */
    public MaintenanceReport delete(String tenantId, String projectId, boolean force) {
        ProjectDocument project = require(tenantId, projectId);
        requireNotSystem(project, "delete");
        requireNoLiveLease(project, force, "delete");

        return sweepAndDelete(tenantId, projectId, /*hub*/ false);
    }

    /**
     * Removes a per-user hub project — the one SYSTEM project that
     * legitimately ends, because its name encodes a login that is going away.
     *
     * <p><b>Only ever call this while deleting the account it belongs to.</b>
     * It exists because {@link #delete} refuses SYSTEM projects and must keep
     * refusing them; a flag on that method would reach {@code _vance} just as
     * easily. Both this and {@link ProjectService#deleteUserHub} verify the
     * {@code _user_<login>} shape independently.
     *
     * @throws IllegalArgumentException if {@code hubProjectName} is not a hub
     */
    public MaintenanceReport deleteUserHub(String tenantId, String hubProjectName) {
        requireUserHub(hubProjectName, "delete");
        require(tenantId, hubProjectName);
        return sweepAndDelete(tenantId, hubProjectName, /*hub*/ true);
    }

    /**
     * Runs every handler, then removes the project document — but only if all
     * of them succeeded.
     *
     * <p>The invariant that makes an interrupted delete recoverable: the
     * document is the index back to the project's data, so dropping it while
     * data is left strands that data with no way to address it. Re-running the
     * same command finishes the job, which is why every handler must be
     * idempotent.
     */
    private MaintenanceReport sweepAndDelete(String tenantId, String projectId, boolean hub) {
        List<UnaccountedCollection> unaccounted = unaccountedCollections(tenantId, projectId);
        List<EntityResult> entities = new ArrayList<>();
        boolean allSucceeded = true;
        for (ProjectDataHandler handler : handlers) {
            // Asked first: a handler that deliberately leaves something behind
            // can only count it while it is still there.
            String note = deleteNote(handler, tenantId, projectId);
            EntityResult result = run(handler, () -> handler.delete(tenantId, projectId));
            boolean succeeded = result.note() == null;
            allSucceeded &= succeeded;
            entities.add(succeeded && note != null
                    ? new EntityResult(result.handlerId(), result.collections(),
                            result.affected(), note)
                    : result);
        }
        if (allSucceeded) {
            if (hub) {
                projectService.deleteUserHub(tenantId, projectId);
            } else {
                projectService.delete(tenantId, projectId);
            }
            entities.add(EntityResult.of("project", Set.of("projects"), 1));
        } else {
            entities.add(new EntityResult("project", Set.of("projects"), 0,
                    "kept — an entity failed, re-run the delete to finish"));
            log.warn("Project '{}/{}' delete incomplete — project document kept",
                    tenantId, projectId);
        }
        return new MaintenanceReport(tenantId, projectId, Operation.DELETE,
                entities, unaccounted);
    }

    private static void requireUserHub(String name, String operation) {
        if (!ProjectService.isUserHub(name)) {
            throw new IllegalArgumentException(
                    "Refusing to " + operation + " '" + name + "' as a user hub — only projects"
                            + " named '" + ProjectService.HUB_PROJECT_NAME_PREFIX
                            + "<login>' qualify");
        }
    }

    // ─── Rename ────────────────────────────────────────────────────────────

    /**
     * Carries the project's name to {@code newProjectId}, rewriting every
     * structured reference to it.
     *
     * <p>Structured means <em>fields</em>: {@code projectId} columns, the
     * settings scope, tool-health and permission scope ids, the workspace
     * directory. References inside document <em>content</em> — a {@code vance:}
     * URI in a markdown body, a project name in a recipe — are not touched;
     * rewriting free text with a pattern would hit code blocks and quotations
     * as readily as real links. What the handlers can see of that is counted
     * and reported instead, so the operator knows what is left to do rather
     * than finding out later.
     *
     * @param force skip the live-lease guard, as in {@link #delete}
     * @throws RenameBlockedException if any handler cannot carry the rename —
     *     checked for all handlers before the first one writes
     */
    public MaintenanceReport rename(
            String tenantId, String projectId, String newProjectId, boolean force) {
        ProjectDocument project = require(tenantId, projectId);
        requireNotSystem(project, "rename");
        requireNoLiveLease(project, force, "rename");
        if (projectId.equals(newProjectId)) {
            throw new IllegalArgumentException(
                    "Project is already called '" + newProjectId + "'");
        }
        if (projectService.existsByTenantAndName(tenantId, newProjectId)) {
            throw new ProjectService.ProjectAlreadyExistsException(
                    "Project '" + newProjectId + "' already exists in tenant '" + tenantId + "'");
        }

        return sweepAndRename(tenantId, projectId, newProjectId, /*hub*/ false);
    }

    /**
     * Carries a per-user hub project to the login its owner now has. Same
     * narrow door as {@link #deleteUserHub}, and for the same reason: the hub's
     * name <em>is</em> the login, so a renamed account whose hub kept the old
     * name has a hub nobody looks for.
     *
     * @throws IllegalArgumentException if either name is not a hub
     */
    public MaintenanceReport renameUserHub(
            String tenantId, String hubProjectName, String newHubProjectName) {
        requireUserHub(hubProjectName, "rename");
        requireUserHub(newHubProjectName, "rename to");
        require(tenantId, hubProjectName);
        return sweepAndRename(tenantId, hubProjectName, newHubProjectName, /*hub*/ true);
    }

    private MaintenanceReport sweepAndRename(
            String tenantId, String projectId, String newProjectId, boolean hub) {
        // Ask everybody first. A rename that stops halfway leaves the tenant
        // split between two names, which is worse than one that never started.
        List<String> blockers = new ArrayList<>();
        for (ProjectDataHandler handler : handlers) {
            String blocker;
            try {
                blocker = handler.renameBlocker(tenantId, projectId, newProjectId);
            } catch (RuntimeException e) {
                blocker = "could not be asked: " + e;
            }
            if (blocker != null) {
                blockers.add(handler.id() + ": " + blocker);
            }
        }
        if (!blockers.isEmpty()) {
            throw new RenameBlockedException(blockers);
        }

        List<EntityResult> entities = new ArrayList<>();
        for (ProjectDataHandler handler : handlers) {
            entities.add(run(handler, () -> handler.rename(tenantId, projectId, newProjectId)));
        }
        // Last: while the document still says the old name, a half-finished
        // rename is at least addressable under it.
        if (hub) {
            projectService.renameUserHub(tenantId, projectId, newProjectId);
        } else {
            projectService.rename(tenantId, projectId, newProjectId);
        }
        entities.add(EntityResult.of("project", Set.of("projects"), 1));

        return new MaintenanceReport(tenantId, projectId, Operation.RENAME,
                entities, unaccountedCollections(tenantId, newProjectId));
    }

    // ─── Coverage probe ────────────────────────────────────────────────────

    /**
     * Collections holding rows for this project that no handler claims.
     *
     * <p>Asks the database, not the code — that is the point. A collection is
     * examined when (a) no handler names it and (b) a sample of its documents
     * shows a {@code projectId} field; only then is the counting query worth
     * its cost. See {@link #PROBE_SAMPLE_SIZE} for what that sampling gives up.
     */
    public List<UnaccountedCollection> unaccountedCollections(String tenantId, String projectId) {
        Set<String> claimed = new HashSet<>();
        for (ProjectDataHandler handler : handlers) {
            claimed.addAll(handler.collections());
        }
        claimed.add(mongoTemplate.getCollectionName(ProjectDocument.class));

        List<UnaccountedCollection> found = new ArrayList<>();
        for (String collection : mongoTemplate.getCollectionNames()) {
            if (claimed.contains(collection) || !mentionsProjectId(collection)) {
                continue;
            }
            long count = mongoTemplate.count(
                    new Query(Criteria.where("projectId").is(projectId)), collection);
            if (count > 0) {
                found.add(new UnaccountedCollection(collection, count));
                log.warn("Collection '{}' holds {} row(s) for project '{}/{}' but no"
                                + " ProjectDataHandler claims it — add one, or that data"
                                + " outlives the project",
                        collection, count, tenantId, projectId);
            }
        }
        return found;
    }

    /** Whether a sample of {@code collection} shows any {@code projectId} field. */
    private boolean mentionsProjectId(String collection) {
        List<Document> sample = mongoTemplate.find(
                new Query().limit(PROBE_SAMPLE_SIZE), Document.class, collection);
        return sample.stream().anyMatch(doc -> doc.containsKey("projectId"));
    }

    // ─── Guards ────────────────────────────────────────────────────────────

    private ProjectDocument require(String tenantId, String projectId) {
        Optional<ProjectDocument> found =
                projectService.findByTenantAndName(tenantId, projectId);
        return found.orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                "Project '" + projectId + "' not found in tenant '" + tenantId + "'"));
    }

    private void requireNotSystem(ProjectDocument project, String operation) {
        if (project.getKind() == de.mhus.vance.shared.project.ProjectKind.SYSTEM) {
            throw new ProjectService.SystemProjectProtectedException(
                    "Project '" + project.getName() + "' is SYSTEM — cannot " + operation);
        }
    }

    private void requireNoLiveLease(ProjectDocument project, boolean force, String operation) {
        if (force) {
            return;
        }
        Optional<String> holder =
                ProjectOwnership.liveOwnerPodId(project, Instant.now(), leaseTtl);
        if (holder.isPresent()) {
            throw new ProjectInUseException(
                    "Project '" + project.getName() + "' is held by pod '" + holder.get()
                            + "' (node '" + project.getHomeNode() + "') — suspend it or wait for"
                            + " the lease to expire before you " + operation
                            + " it. Use --force if the holder is known to be gone.");
        }
    }

    /** A handler's delete note, or {@code null} — never a reason to fail. */
    private @org.jspecify.annotations.Nullable String deleteNote(
            ProjectDataHandler handler, String tenantId, String projectId) {
        try {
            return handler.deleteNote(tenantId, projectId);
        } catch (RuntimeException e) {
            log.warn("Delete note from handler '{}' failed: {}", handler.id(), e.toString());
            return null;
        }
    }

    /** Runs one handler operation, turning a failure into a recorded note. */
    private EntityResult run(ProjectDataHandler handler, HandlerCall call) {
        try {
            return EntityResult.of(handler.id(), handler.collections(), call.execute());
        } catch (RuntimeException e) {
            log.error("Project maintenance handler '{}' failed", handler.id(), e);
            return new EntityResult(handler.id(), handler.collections(), 0,
                    "FAILED: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface HandlerCall {
        long execute();
    }

    /** A pod is working with the project right now. */
    public static class ProjectInUseException extends RuntimeException {
        public ProjectInUseException(String message) {
            super(message);
        }
    }

    /** At least one entity cannot carry the rename; nothing was written. */
    public static class RenameBlockedException extends RuntimeException {
        private final List<String> blockers;

        public RenameBlockedException(List<String> blockers) {
            super("Rename blocked: " + String.join("; ", blockers));
            this.blockers = List.copyOf(blockers);
        }

        public List<String> blockers() {
            return blockers;
        }
    }
}
