package de.mhus.vance.anus.maintenance;

import de.mhus.vance.anus.cluster.ProjectClusterService;
import de.mhus.vance.anus.cluster.ProjectClusterService.DrainDecision;
import de.mhus.vance.anus.maintenance.MaintenanceReport.EntityResult;
import de.mhus.vance.anus.maintenance.MaintenanceReport.Operation;
import de.mhus.vance.anus.maintenance.MaintenanceReport.UnaccountedCollection;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Removes a whole tenant: every project, every account, and every row anything keyed by it. Irreversible.
 *
 * <p>It composes what already exists rather than knowing the data itself. Projects go through
 * {@link ProjectMaintenanceService} (each drained off its pod first), accounts through
 * {@link UserMaintenanceService} — which also takes their hubs — so every handler that knows how to clear a
 * project or an account's data does so, including what lives outside Mongo. What those do not reach, the sweep
 * does: every mapped document that declares a {@code tenantId} ({@link TenantKeyedCollections}) is cleared of the
 * tenant's rows, which covers the tenant's own settings, teams, keys, roles, ledgers and whatever is added later.
 *
 * <h2>Guards</h2>
 *
 * <p>The system tenant and {@code _}-prefixed names are refused, and so is a tenant that is still enabled —
 * both by {@link TenantService#delete}, but checked here first so nothing is touched before the refusal. Switching
 * the tenant off is the deliberate step before: it is what makes sure nobody is signing in while the data goes.
 *
 * <h2>Failure policy</h2>
 *
 * <p>Same as the projects': a step that fails does not stop the run, its failure is a note in the report, and the
 * tenant row — the index back to the data — is removed only if every step succeeded <em>and</em> no collection
 * still holds rows for the tenant that nothing claims. The re-run is the recovery path, so every step is
 * idempotent: a project that is already gone is simply not there to delete.
 *
 * <p>Not covered, said plainly: anything outside Mongo that no handler knows (a runner's pod, a gateway tenant);
 * those are the caller's to clear first. And a collection written without a document class is only <em>reported</em>.
 */
@Slf4j
@Service
public class TenantMaintenanceService {

    /** Rows sampled from an unclaimed collection to see whether it is tenant-keyed at all. */
    private static final int PROBE_SAMPLE_SIZE = 20;

    private final TenantService tenants;
    private final ProjectService projects;
    private final UserService users;
    private final ProjectMaintenanceService projectMaintenance;
    private final UserMaintenanceService userMaintenance;
    private final ProjectClusterService cluster;
    private final MongoTemplate mongo;

    private volatile @Nullable Set<String> keyedCollections;

    public TenantMaintenanceService(
            TenantService tenants,
            ProjectService projects,
            UserService users,
            ProjectMaintenanceService projectMaintenance,
            UserMaintenanceService userMaintenance,
            ProjectClusterService cluster,
            MongoTemplate mongo) {
        this.tenants = tenants;
        this.projects = projects;
        this.users = users;
        this.projectMaintenance = projectMaintenance;
        this.userMaintenance = userMaintenance;
        this.cluster = cluster;
        this.mongo = mongo;
    }

    /** The collections this process sweeps for a tenant. */
    public Set<String> keyedCollections() {
        Set<String> cached = keyedCollections;
        if (cached == null) {
            cached = TenantKeyedCollections.scan(mongo);
            keyedCollections = cached;
        }
        return cached;
    }

    // ─── Inspect ───────────────────────────────────────────────────────────

    /**
     * Counts what the tenant owns, writing nothing: the projects, the accounts, and the rows in every tenant-keyed
     * collection. The dry run for {@link #delete}.
     *
     * @throws TenantService.TenantNotFoundException if the tenant does not exist
     */
    public MaintenanceReport inspect(String tenantId) {
        require(tenantId);
        List<EntityResult> entities = new ArrayList<>();
        entities.add(EntityResult.of(
                "projects", Set.of("projects"), projects.all(tenantId).size()));
        entities.add(
                EntityResult.of("users", Set.of("users"), users.all(tenantId).size()));
        for (String collection : keyedCollections()) {
            entities.add(EntityResult.of("rows:" + collection, Set.of(collection), count(collection, tenantId)));
        }
        return MaintenanceReport.of(tenantId, tenantId, Operation.INSPECT, entities, unaccountedCollections(tenantId));
    }

    // ─── Delete ────────────────────────────────────────────────────────────

    /**
     * Removes the tenant and everything it owns.
     *
     * @param force passed to the project and account deletes: skip the live-lease guard and the accounts'
     *     blockers, and go on when a project could not be drained — only for a holder known to be gone
     * @throws TenantService.TenantProtectedException for the system tenant and {@code _}-prefixed names
     * @throws TenantService.TenantStillEnabledException if the tenant is enabled
     * @throws TenantService.TenantNotFoundException if the tenant does not exist
     */
    public MaintenanceReport delete(String tenantId, boolean force) {
        TenantDocument tenant = require(tenantId);
        requireRemovable(tenant);

        List<EntityResult> entities = new ArrayList<>();
        boolean allSucceeded = true;

        // 1. Projects, drained and deleted one by one. A hub goes with its account, a SYSTEM project is data-only.
        List<ProjectDocument> all = projects.all(tenantId);
        for (ProjectDocument project : all) {
            if (ProjectService.isUserHub(project.getName()) || project.getKind() == ProjectKind.SYSTEM) {
                continue;
            }
            allSucceeded &= deleteProject(tenantId, project.getName(), force, entities);
        }

        // 2. Accounts, hubs included.
        for (UserDocument user : users.all(tenantId)) {
            allSucceeded &= deleteUser(tenantId, user.getName(), force, entities);
        }

        // 3. What is left of the projects: SYSTEM projects (the tenant's own) and hubs whose account was gone
        //    before — their data goes through the handlers, their rows through the sweep below.
        for (ProjectDocument project : projects.all(tenantId)) {
            allSucceeded &= clearProjectData(tenantId, project.getName(), entities);
        }

        // 4. Everything else keyed by the tenant.
        allSucceeded &= sweep(tenantId, entities);

        // 5. The probe after the sweep: rows nobody claims keep the tenant row in place.
        List<UnaccountedCollection> unaccounted = unaccountedCollections(tenantId);
        boolean clean = allSucceeded && unaccounted.isEmpty();

        if (clean) {
            tenants.delete(tenantId);
            entities.add(EntityResult.of("tenant", Set.of("tenants"), 1));
        } else {
            entities.add(new EntityResult(
                    "tenant",
                    Set.of("tenants"),
                    0,
                    unaccounted.isEmpty()
                            ? "kept — a step failed, re-run the delete to finish"
                            : "kept — collections hold rows for this tenant that nothing claims"));
            log.warn("Tenant '{}' delete incomplete — tenant row kept", tenantId);
        }
        return new MaintenanceReport(tenantId, tenantId, Operation.DELETE, entities, unaccounted, clean);
    }

    private boolean deleteProject(String tenantId, String name, boolean force, List<EntityResult> out) {
        String id = "project:" + name;
        try {
            DrainDecision drained = cluster.drainBefore(tenantId, name, false, force);
            if (drained.abort()) {
                out.add(failed(id, "could not be handed off its pod — retry when it is idle, or force"));
                return false;
            }
            MaintenanceReport report = projectMaintenance.delete(tenantId, name, force);
            out.add(new EntityResult(
                    id, Set.of("projects"), report.total(), report.complete() ? null : "incomplete — see the report"));
            return report.complete();
        } catch (RuntimeException e) {
            out.add(failed(id, e.getMessage()));
            return false;
        }
    }

    private boolean deleteUser(String tenantId, String name, boolean force, List<EntityResult> out) {
        String id = "user:" + name;
        try {
            MaintenanceReport report = userMaintenance.delete(tenantId, name, force);
            out.add(new EntityResult(
                    id, Set.of("users"), report.total(), report.complete() ? null : "incomplete — see the report"));
            return report.complete();
        } catch (RuntimeException e) {
            out.add(failed(id, e.getMessage()));
            return false;
        }
    }

    /** Runs every project handler for a project that stays a document until the sweep. Idempotent. */
    private boolean clearProjectData(String tenantId, String project, List<EntityResult> out) {
        boolean ok = true;
        for (ProjectDataHandler handler : projectMaintenance.handlers()) {
            try {
                out.add(EntityResult.of(
                        "project-data:" + project + ":" + handler.id(),
                        handler.collections(),
                        handler.delete(tenantId, project)));
            } catch (RuntimeException e) {
                out.add(failed("project-data:" + project + ":" + handler.id(), e.getMessage()));
                ok = false;
            }
        }
        return ok;
    }

    /** Clears the tenant's rows from every keyed collection. */
    private boolean sweep(String tenantId, List<EntityResult> out) {
        boolean ok = true;
        for (String collection : keyedCollections()) {
            try {
                long removed = mongo.remove(tenantQuery(tenantId), collection).getDeletedCount();
                out.add(EntityResult.of("rows:" + collection, Set.of(collection), removed));
            } catch (RuntimeException e) {
                out.add(failed("rows:" + collection, e.getMessage()));
                ok = false;
            }
        }
        return ok;
    }

    // ─── Probe ─────────────────────────────────────────────────────────────

    /**
     * Collections no document class claims that hold rows for this tenant. Sampled, not counted blindly — a
     * count over a collection without an index is a full scan, and some collections are enormous — so a
     * collection whose first rows all omit the field escapes. Reported, never acted on.
     */
    List<UnaccountedCollection> unaccountedCollections(String tenantId) {
        Set<String> known = keyedCollections();
        List<UnaccountedCollection> found = new ArrayList<>();
        for (String collection : mongo.getCollectionNames()) {
            if (collection.startsWith("system.") || "tenants".equals(collection) || known.contains(collection)) {
                continue;
            }
            if (!sampleHasTenantId(collection)) {
                continue;
            }
            long count = count(collection, tenantId);
            if (count > 0) {
                found.add(new UnaccountedCollection(collection, count));
            }
        }
        return found;
    }

    private boolean sampleHasTenantId(String collection) {
        for (Document row : mongo.find(new Query().limit(PROBE_SAMPLE_SIZE), Document.class, collection)) {
            if (row.containsKey(TenantKeyedCollections.FIELD)) {
                return true;
            }
        }
        return false;
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private TenantDocument require(String tenantId) {
        return tenants.findByName(tenantId)
                .orElseThrow(() -> new TenantService.TenantNotFoundException("Tenant '" + tenantId + "' not found"));
    }

    /** The refusals that must come before anything is touched; {@link TenantService#delete} repeats them last. */
    private static void requireRemovable(TenantDocument tenant) {
        String name = tenant.getName();
        if (TenantService.SYSTEM_TENANT.equals(name) || name.startsWith("_")) {
            throw new TenantService.TenantProtectedException(
                    "Tenant '" + name + "' is a system tenant and is never removed");
        }
        if (tenant.isEnabled()) {
            throw new TenantService.TenantStillEnabledException(
                    "Tenant '" + name + "' is still enabled — disable it first");
        }
    }

    private long count(String collection, String tenantId) {
        return mongo.count(tenantQuery(tenantId), collection);
    }

    private static Query tenantQuery(String tenantId) {
        return new Query(Criteria.where(TenantKeyedCollections.FIELD).is(tenantId));
    }

    private static EntityResult failed(String id, @Nullable String why) {
        return new EntityResult(id, Set.of(), 0, "FAILED — " + (why == null ? "no message" : why));
    }
}
