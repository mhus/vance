package de.mhus.vance.simpleauth;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionResolver;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.SubjectType;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * The bundled role-based resolver. Implements rules R1–R7 (see
 * {@code specification/public/permission-system.md} §5). Stateless apart from
 * the (cached) grant lookup via {@link PermissionGrantService}; never throws —
 * returns {@code false} on any missing data (fail-closed).
 */
@Slf4j
public class MongoPermissionResolver implements PermissionResolver {

    private static final String TENANT_PROJECT = "_tenant";
    private static final String VANCE_PROJECT = "_vance";
    private static final List<String> RESERVED_PATH_PREFIXES = List.of("_vance/", "recipes/");

    /** Hot setting: when true, deny verdicts are logged + counted but allowed. */
    static final String SHADOW_SETTING = "vance.permission.shadow";
    private static final String METRIC_CHECKS = "vance.permission.checks";

    private final PermissionGrantService grants;
    private final TeamService teamService;
    private final SettingService settingService;
    private final @Nullable MetricService metricService;

    public MongoPermissionResolver(PermissionGrantService grants, TeamService teamService,
            SettingService settingService, @Nullable MetricService metricService) {
        this.grants = grants;
        this.teamService = teamService;
        this.settingService = settingService;
        this.metricService = metricService;
    }

    @Override
    public boolean isAllowed(SecurityContext subject, Resource resource, Action action) {
        boolean allowed = computeAllowed(subject, resource, action);
        String resourceName = resource.getClass().getSimpleName();
        if (allowed) {
            count("allow", resourceName);
            return true;
        }
        // Shadow mode: compute the real (deny) verdict but let it through,
        // logging + counting it so an operator can see which legitimate
        // flows a sharp cut would break before flipping shadow off
        // (permission-system-concept §7.3). Read per-tenant so it's hot.
        if (isShadow(subject)) {
            count("would_deny", resourceName);
            log.warn("permission SHADOW would-deny subject={}:{} action={} resource={}",
                    subject.subjectType(), subject.subjectId(), action, resource);
            return true;
        }
        count("deny", resourceName);
        return false;
    }

    private boolean isShadow(SecurityContext subject) {
        try {
            return settingService.getBooleanValueCascade(
                    subject.tenantId(), null, null, SHADOW_SETTING, false);
        } catch (RuntimeException e) {
            // Never let a setting-lookup failure silently open access.
            return false;
        }
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
            // R1 — internal callers are trusted.
            if (subject.subjectType() == SubjectType.SYSTEM) {
                return true;
            }
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
        GrantRole required = minRole(action);
        if (isWrite(action) && isReservedPath(d.path())) {
            // Reserved paths (_vance/**, recipes/**, scheduler/hook/event YAMLs)
            // may only be written by ADMIN (or SYSTEM via R1).
            required = GrantRole.ADMIN;
        }
        return roleOnProject(subject, d.tenantId(), d.projectName(), required);
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
        // _vance and other users' hubs: tenant-ADMIN only.
        if (project.equals(VANCE_PROJECT) || project.startsWith(ProjectService.SYSTEM_NAME_PREFIX)) {
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
