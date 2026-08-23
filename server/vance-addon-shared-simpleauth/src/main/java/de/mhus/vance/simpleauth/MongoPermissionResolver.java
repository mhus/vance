package de.mhus.vance.simpleauth;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionResolver;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteReason;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * The bundled role-based resolver. Implements the user-policy rules R2–R7 (see
 * {@code specification/public/permission-system.md} §5). R1 — the SYSTEM trust
 * boundary — is <em>not</em> here: {@link PermissionService} short-circuits a
 * SYSTEM subject / SYSTEM write-reason before delegation, so this provider only
 * ever sees genuine user policy. Stateless apart from the (cached) grant lookup
 * via {@link PermissionGrantService}; never throws — returns {@code false} on
 * any missing data (fail-closed).
 */
@Slf4j
public class MongoPermissionResolver implements PermissionResolver {

    private static final String TENANT_PROJECT = "_tenant";
    /**
     * Reserved document paths — WRITE here is server-only (R4). The whole
     * {@code _vance/} namespace is reserved: recipes, models, manuals,
     * setting-forms, wizards, templates and the auto-executing scheduler/hooks/
     * events all shape system behaviour, so writing any of them — even a
     * project-local override in a normal project — is denied for every user
     * actor, ADMIN included. Only trusted server code writes it, via
     * {@link WriteReason#SYSTEM}, which {@link PermissionService} short-circuits
     * upstream so it never reaches this resolver (internal log/recipe/kit writes
     * are unaffected). Reading stays open (the recipe/model/settings cascade
     * resolves here for every user).
     */
    private static final List<String> RESERVED_PATH_PREFIXES = List.of("_vance/");

    private static final String METRIC_CHECKS = "vance.permission.checks";

    private final PermissionGrantService grants;
    private final TeamService teamService;
    private final @Nullable MetricService metricService;

    public MongoPermissionResolver(PermissionGrantService grants, TeamService teamService,
            @Nullable MetricService metricService) {
        this.grants = grants;
        this.teamService = teamService;
        this.metricService = metricService;
    }

    @Override
    public boolean isAllowed(SecurityContext subject, Resource resource, Action action) {
        // This provider evaluates user policy only. The SYSTEM trust boundary
        // (SYSTEM subject / SYSTEM write-reason) is short-circuited by
        // PermissionService before delegation, so it never reaches here.
        boolean allowed = computeAllowed(subject, resource, action);
        // Metric only — the verdict is always enforced (fail-closed). The
        // deny counter (vance.permission.checks{outcome=deny}) is the
        // diagnosis surface for tightening grants.
        count(allowed ? "allow" : "deny", resource.getClass().getSimpleName());
        return allowed;
    }

    private void count(String outcome, String resourceName) {
        if (metricService == null) {
            return;
        }
        try {
            metricService.counter(METRIC_CHECKS, "outcome", outcome, "resource", resourceName)
                    .increment();
        } catch (RuntimeException ignore) {
            // metrics are best-effort; never affect the verdict
        }
    }

    private boolean computeAllowed(SecurityContext subject, Resource resource, Action action) {
        try {
            // Tenant guard — a USER never acts cross-tenant.
            if (!subject.tenantId().equals(tenantOf(resource))) {
                return false;
            }
            return switch (resource) {
                case Resource.InboxItem i -> inboxAllowed(subject, i);                       // R5
                case Resource.Document d -> documentAllowed(subject, d, action);             // R4 + R3
                case Resource.Project p -> roleOnProject(subject, p.tenantId(), p.projectName(), minRole(action));
                case Resource.Session s -> roleOnProject(subject, s.tenantId(), s.projectName(), minRole(action));
                case Resource.ThinkProcess tp -> roleOnProject(subject, tp.tenantId(), tp.projectName(), minRole(action));
                case Resource.Setting st -> settingAllowed(subject, st, action);
                case Resource.Tenant t -> tenantAllowed(subject, t.tenantId(), minRole(action)); // R2
                case Resource.Team tm -> hasRole(tenantRole(subject, tm.tenantId()), GrantRole.ADMIN);
                case Resource.User u -> hasRole(tenantRole(subject, u.tenantId()), GrantRole.ADMIN);
            };
        } catch (RuntimeException e) {
            // Fail-closed: never let a lookup error open access.
            log.warn("permission resolve failed subject={}:{} action={} resource={}: {}",
                    subject.subjectType(), subject.subjectId(), action, resource, e.toString());
            return false;
        }
    }

    // ── Document (R4 reserved-prefix, then R3 project inheritance) ──

    private boolean documentAllowed(SecurityContext subject, Resource.Document d, Action action) {
        if (isWrite(action) && isReservedPath(d.path())) {
            // The _vance/ config namespace is server-owned: a normal user
            // (READER/WRITER) is read-only here, but an ADMIN may edit it
            // directly — project ADMIN, or tenant ADMIN via R3 inheritance.
            // WriteReason.SYSTEM is the additive elevation channel (a vouched
            // SYSTEM action is allowed regardless of the actor's role, audited
            // via the real subject) and is short-circuited upstream in
            // PermissionService before this resolver runs; a $meta.privileged/
            // runAs document keeps its own extra ADMIN gate in DocumentService.
            // READ falls through to the normal project rule.
            return roleOnProject(subject, d.tenantId(), d.projectName(), GrantRole.ADMIN);
        }
        return roleOnProject(subject, d.tenantId(), d.projectName(), minRole(action));
    }

    // ── Setting (inherits from its reference scope) ──

    private boolean settingAllowed(SecurityContext subject, Resource.Setting st, Action action) {
        GrantRole required = minRole(action);
        return switch (st.referenceType()) {
            case "project" -> roleOnProject(subject, st.tenantId(), st.referenceId(), required);
            case "user" -> st.referenceId().equals(subject.subjectId())    // own settings
                    || hasRole(tenantRole(subject, st.tenantId()), GrantRole.ADMIN);
            default -> tenantAllowed(subject, st.tenantId(), required);     // "tenant" and unknown → tenant scope
        };
    }

    // ── Project inheritance (R3) with podless-owner special cases (R7) ──

    private boolean roleOnProject(SecurityContext subject, String tenantId, String project, GrantRole required) {
        if (ProjectService.isPodless(project)) {
            return podlessAllowed(subject, tenantId, project, required);
        }
        return hasRole(effectiveRole(subject, tenantId, project), required);
    }

    private boolean podlessAllowed(SecurityContext subject, String tenantId, String project, GrantRole required) {
        // Owner of the personal hub _user_<login> has implicit ADMIN on it.
        if (project.equals(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + subject.subjectId())) {
            return true;
        }
        // _tenant: every tenant member may READ (settings-cascade defaults);
        // writing needs tenant-ADMIN.
        if (project.equals(TENANT_PROJECT)) {
            if (required == GrantRole.READER) {
                return true;
            }
            return hasRole(tenantRole(subject, tenantId), GrantRole.ADMIN);
        }
        // Other users' hubs (_user_<other>) and any other system project:
        // tenant-ADMIN only.
        if (project.startsWith(ProjectService.SYSTEM_NAME_PREFIX)) {
            return hasRole(tenantRole(subject, tenantId), GrantRole.ADMIN);
        }
        return hasRole(effectiveRole(subject, tenantId, project), required);
    }

    // ── Tenant (R2 — implicit READ for every member) ──

    private boolean tenantAllowed(SecurityContext subject, String tenantId, GrantRole required) {
        if (required == GrantRole.READER) {
            return true; // implicit Tenant READ for any authenticated member
        }
        return hasRole(tenantRole(subject, tenantId), required);
    }

    // ── Inbox (R5) ──

    /**
     * Rule R5: an item is accessible to its assignee, or to someone sharing a
     * team with the assignee. An item without an assignee is never accessible.
     *
     * <p><b>The same rule exists a second time</b>, in
     * {@code InboxAuthz#mayDecide} (vance-brain), which the REST surface uses.
     * The duplication is forced by the module boundary — this addon builds on
     * {@code vance-shared} and must not depend on {@code vance-brain}.
     * <b>Change both or neither:</b> the copies are only useful while they
     * agree, or REST and WS authorize the same request differently.
     *
     * <p>Note what is deliberately <b>not</b> here: a thread's participants and
     * its declared team also grant read access, but that is decided in
     * {@code InboxAuthz#maySee} as a property of the document, not asked of a
     * resolver. Keeping it out leaves {@code Resource.InboxItem} unchanged —
     * otherwise every implementation, including the EE governor, would have to
     * grow a field. See {@code planning/maximegalon.md} §5.
     */
    private boolean inboxAllowed(SecurityContext subject, Resource.InboxItem item) {
        String assignee = item.assignedToUserId();
        if (assignee == null || assignee.isBlank()) {
            return false;
        }
        if (assignee.equals(subject.subjectId())) {
            return true;
        }
        return sharesTeam(subject, item.tenantId(), assignee);
    }

    private boolean sharesTeam(SecurityContext subject, String tenantId, String otherUser) {
        if (subject.teams().isEmpty()) {
            return false;
        }
        for (TeamDocument t : teamService.byMember(tenantId, otherUser)) {
            if (subject.teams().contains(t.getName())) {
                return true;
            }
        }
        return false;
    }

    // ── effective role: max over user-grants, team-grants, tenant-grant ──

    private @Nullable GrantRole effectiveRole(SecurityContext subject, String tenantId, String project) {
        GrantRole best = null;
        for (PermissionGrantDocument g : grants.forScope(tenantId, GrantScopeType.PROJECT, project)) {
            if (matchesSubject(g, subject)) {
                best = maxNullable(best, g.getRole());
            }
        }
        return maxNullable(best, tenantRole(subject, tenantId));
    }

    private @Nullable GrantRole tenantRole(SecurityContext subject, String tenantId) {
        GrantRole best = null;
        for (PermissionGrantDocument g : grants.forScope(tenantId, GrantScopeType.TENANT, tenantId)) {
            if (matchesSubject(g, subject)) {
                best = maxNullable(best, g.getRole());
            }
        }
        return best;
    }

    private static boolean matchesSubject(PermissionGrantDocument g, SecurityContext subject) {
        return switch (g.getSubjectType()) {
            case USER -> g.getSubjectId().equals(subject.subjectId());
            case TEAM -> subject.teams().contains(g.getSubjectId());
        };
    }

    // ── helpers ──

    private static GrantRole minRole(Action action) {
        return switch (action) {
            case READ -> GrantRole.READER;
            case WRITE, CREATE, DELETE, START, EXECUTE -> GrantRole.WRITER;
            case ADMIN, IMPERSONATE -> GrantRole.ADMIN;
        };
    }

    private static boolean isWrite(Action action) {
        return switch (action) {
            case WRITE, CREATE, DELETE, IMPERSONATE -> true;
            default -> false;
        };
    }

    private static boolean isReservedPath(String path) {
        for (String prefix : RESERVED_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRole(@Nullable GrantRole actual, GrantRole required) {
        return actual != null && actual.atLeast(required);
    }

    private static @Nullable GrantRole maxNullable(@Nullable GrantRole a, @Nullable GrantRole b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return GrantRole.max(a, b);
    }

    private static String tenantOf(Resource resource) {
        return switch (resource) {
            case Resource.Tenant t -> t.tenantId();
            case Resource.Project p -> p.tenantId();
            case Resource.Document d -> d.tenantId();
            case Resource.Setting s -> s.tenantId();
            case Resource.Session s -> s.tenantId();
            case Resource.ThinkProcess tp -> tp.tenantId();
            case Resource.Team tm -> tm.tenantId();
            case Resource.User u -> u.tenantId();
            case Resource.InboxItem i -> i.tenantId();
        };
    }
}
