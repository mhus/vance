package de.mhus.vance.shared.permission;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.user.UserService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The single entry point for permission checks across the server.
 *
 * <p>Inbound layers call {@link #enforce} before delegating to a service.
 * Internal callers either pass a real {@link SecurityContext} they were given,
 * or {@link SecurityContext#SYSTEM} when running outside any user request.
 *
 * <p>A check runs five stages; only the fourth can allow, the others can only
 * deny:
 *
 * <ol>
 *   <li><b>Credential confinement</b> — a project-pinned credential never
 *       reaches outside its project.</li>
 *   <li><b>SYSTEM trust</b> — the server acting as itself, or server code
 *       vouching for a write ({@link WriteReason#SYSTEM}), is allowed.</li>
 *   <li><b>Core preconditions</b> ({@link CorePreconditions}) — the structural
 *       rules: tenant guard, {@code _tenant}, system projects, {@code _vance/},
 *       no reach above the own role.</li>
 *   <li><b>Role table</b> ({@link RoleTable}) — the roles the
 *       {@link PermissionProvider} reports, applied to what each role
 *       permits.</li>
 *   <li><b>Provider veto</b> — {@link PermissionProvider#vetoes}.</li>
 * </ol>
 *
 * <p><b>Exactly one provider is mandatory.</b> The core contributes the local
 * provider unless an addon contributes another; the constructor fails startup
 * fast for none or several, so a context can never boot without authorization
 * or with two ambiguous role sources.
 *
 * <p>Call sites speak {@code (Resource, Action)} only. The role-shaped methods
 * here ({@link #tenantRoleOf}, {@link #projectRoleOf}, {@link #mayActAs}) are
 * for the few places that are about roles themselves — role administration,
 * showing the caller their own roles, identity crossings.
 */
@Service
@Slf4j
public class PermissionService {

    private static final String METRIC_CHECKS = "vance.permission.checks";

    private final PermissionProvider provider;
    private final TeamMembership teamMembership;
    private @Nullable MetricService metricService;

    public PermissionService(List<PermissionProvider> providers, TeamMembership teamMembership) {
        if (providers.size() != 1) {
            String detail = providers.isEmpty()
                    ? "none — the core's local provider is missing from the context"
                    : providers.stream().map(p -> p.getClass().getName()).toList() + " — load exactly one";
            throw new IllegalStateException(
                    "Vance requires exactly one permission provider; found " + providers.size() + ": " + detail);
        }
        this.provider = providers.get(0);
        this.teamMembership = teamMembership;
    }

    /** Optional — the anus context has no meter registry. */
    @Autowired(required = false)
    public void setMetricService(MetricService metricService) {
        this.metricService = metricService;
    }

    public boolean check(SecurityContext subject, Resource resource, Action action) {
        return check(subject, resource, action, WriteReason.USER);
    }

    /**
     * Reason-aware check — {@code reason} lets server code vouch for a write
     * ({@link WriteReason#SYSTEM}) while {@code subject} still carries the real
     * actor. Only server code passes anything but {@link WriteReason#USER}.
     */
    public boolean check(SecurityContext subject, Resource resource, Action action, WriteReason reason) {
        // Credential attenuation — evaluated FIRST, ahead of the system-trust
        // boundary below. A confined credential (an INTEGRATION token pinned to
        // one project) must not reach outside its project even along a path
        // where server code vouches for the write with WriteReason.SYSTEM: that
        // reason says "this write is legitimate policy-wise", not "this caller
        // may touch another project". Scope and policy are different questions
        // and the scope one is answered first.
        if (!withinCredentialScope(subject, resource)) {
            log.debug(
                    "permission DENY (out of credential scope): subject={} restrictedTo={} action={} resource={}",
                    subject.subjectId(),
                    subject.restrictedToProject(),
                    action,
                    resource);
            return false;
        }
        // Framework trust boundary — before any role is looked at, so no
        // provider can accidentally break internal server operations. A SYSTEM
        // subject (the server acting as itself) or a SYSTEM reason (server code
        // vouching for a write; only Java can set it — never a user surface) is
        // trusted; the real actor stays in `subject` for audit.
        if (subject.subjectType() == SubjectType.SYSTEM || reason == WriteReason.SYSTEM) {
            if (log.isTraceEnabled()) {
                log.trace(
                        "permission ALLOW (system-trust): subject={}:{} tenant={} action={} reason={} resource={}",
                        subject.subjectType(),
                        subject.subjectId(),
                        subject.tenantId(),
                        action,
                        reason,
                        resource);
            }
            return true;
        }
        boolean allowed = evaluate(subject, resource, action);
        count(allowed ? "allow" : "deny", resource.getClass().getSimpleName());
        if (log.isTraceEnabled()) {
            log.trace(
                    "permission {}: subject={}:{} tenant={} action={} resource={}",
                    allowed ? "ALLOW" : "DENY",
                    subject.subjectType(),
                    subject.subjectId(),
                    subject.tenantId(),
                    action,
                    resource);
        }
        return allowed;
    }

    public void enforce(SecurityContext subject, Resource resource, Action action) {
        enforce(subject, resource, action, WriteReason.USER);
    }

    public void enforce(SecurityContext subject, Resource resource, Action action, WriteReason reason) {
        if (!check(subject, resource, action, reason)) {
            throw new PermissionDeniedException(subject, resource, action);
        }
    }

    /**
     * Tenant role of {@code subject}. SYSTEM — the server or the operator shell
     * acting as itself — holds every right and reports
     * {@link TenantRole#TENANT_ADMIN}. Fail-closed to {@link TenantRole#USER}
     * when the provider cannot answer.
     */
    public TenantRole tenantRoleOf(SecurityContext subject) {
        if (subject.subjectType() == SubjectType.SYSTEM) {
            return TenantRole.TENANT_ADMIN;
        }
        return tenantRoleOf(subject.tenantId(), subject.subjectId());
    }

    /** Tenant role of any user; {@link TenantRole#USER} when the provider cannot answer. */
    public TenantRole tenantRoleOf(String tenantId, String username) {
        try {
            return provider.tenantRole(tenantId, username);
        } catch (RuntimeException e) {
            log.warn("tenant role lookup failed tenant='{}' user='{}': {}", tenantId, username, e.toString());
            return TenantRole.USER;
        }
    }

    /**
     * The subject's own role in {@code project}: its membership (directly or
     * through a team), or the owner role on its personal hub. The tenant role
     * is not folded in — an ADMIN without membership gets {@code null} here and
     * is still allowed by the role table.
     */
    public @Nullable ProjectRole projectRoleOf(SecurityContext subject, String project) {
        if (subject.subjectType() == SubjectType.SYSTEM) {
            return ProjectRole.ADMIN;
        }
        try {
            return projectRole(subject, project);
        } catch (RuntimeException e) {
            log.warn(
                    "project role lookup failed tenant='{}' user='{}' project='{}': {}",
                    subject.tenantId(),
                    subject.subjectId(),
                    project,
                    e.toString());
            return null;
        }
    }

    /**
     * Whether {@code actor} may act as, or on behalf of, {@code targetUser} —
     * author a document that the server executes under that identity
     * ({@code runAs}, or the creator of a scheduler/hook/event). Nobody acts as
     * someone with a stronger tenant role: otherwise the rule that nobody grants
     * a role above their own would be bypassed by borrowing an identity that
     * already holds it. SYSTEM may; acting as oneself is always allowed.
     */
    public boolean mayActAs(SecurityContext actor, String targetUser) {
        if (actor.subjectType() == SubjectType.SYSTEM || actor.subjectId().equals(targetUser)) {
            return true;
        }
        return Roles.atLeast(tenantRoleOf(actor), tenantRoleOf(actor.tenantId(), targetUser));
    }

    /** {@link #mayActAs}, throwing {@link PermissionDeniedException} when not. */
    public void enforceMayActAs(SecurityContext actor, String targetUser) {
        if (!mayActAs(actor, targetUser)) {
            throw new PermissionDeniedException(
                    actor, new Resource.User(actor.tenantId(), targetUser), Action.IMPERSONATE);
        }
    }

    /**
     * Whether tenant roles are stored inside Vance — i.e. whether role
     * administration may change them. See {@link PermissionProvider#storesTenantRoles}.
     */
    public boolean storesTenantRoles() {
        return provider.storesTenantRoles();
    }

    private boolean evaluate(SecurityContext subject, Resource resource, Action action) {
        try {
            TenantRole tenantRole = provider.tenantRole(subject.tenantId(), subject.subjectId());
            String project = CorePreconditions.projectOf(resource);
            ProjectRole projectRole = project == null ? null : projectRole(subject, project);
            if (!CorePreconditions.permit(
                    subject,
                    resource,
                    action,
                    tenantRole,
                    projectRole,
                    user -> tenantRoleOf(subject.tenantId(), user))) {
                return false;
            }
            if (!RoleTable.allows(subject, resource, action, tenantRole, projectRole, teamMembership)) {
                return false;
            }
            return !provider.vetoes(subject, resource, action);
        } catch (RuntimeException e) {
            // Fail-closed: a lookup error never opens access.
            log.warn(
                    "permission evaluation failed subject={}:{} action={} resource={}: {}",
                    subject.subjectType(),
                    subject.subjectId(),
                    action,
                    resource,
                    e.toString());
            return false;
        }
    }

    /**
     * The owner of a personal hub holds it as ADMIN — a person. A service
     * account (its name carries the {@code _} prefix, enforced at account
     * creation) holds its own hub only as MEMBER: it acts through agents,
     * unattended, and must not rewrite the {@code _vance/} configuration of its
     * home — a Trillian's exposure, recipes or hooks. Its own bookkeeping there
     * goes through server code as {@link WriteReason#SYSTEM}.
     */
    private @Nullable ProjectRole projectRole(SecurityContext subject, String project) {
        if (CorePreconditions.isOwnHub(subject, project)) {
            return subject.subjectId().startsWith(UserService.SERVICE_ACCOUNT_PREFIX)
                    ? ProjectRole.MEMBER
                    : ProjectRole.ADMIN;
        }
        return provider.projectRole(subject, subject.tenantId(), project);
    }

    private void count(String outcome, String resourceName) {
        MetricService metrics = metricService;
        if (metrics == null) {
            return;
        }
        try {
            metrics.counter(METRIC_CHECKS, "outcome", outcome, "resource", resourceName)
                    .increment();
        } catch (RuntimeException ignore) {
            // metrics are best-effort; never affect the verdict
        }
    }

    /**
     * Whether {@code resource} lies inside the project a confined credential is
     * pinned to. Always true for the normal, unconfined context.
     *
     * <p><b>Fail-closed on anything without a project.</b> A resource that names
     * no project — the tenant itself, a user, a team, a tenant-scoped setting,
     * an inbox item — is not "outside the restriction by accident", it is
     * exactly the kind of reach a project-confined credential must not have. The
     * alternative (allow what we cannot classify) would mean every new
     * {@link Resource} kind silently widens every token already in the field.
     *
     * <p>The switch is exhaustive over the sealed {@link Resource} on purpose:
     * adding a kind stops compiling here, which is the moment to decide whether
     * it carries a project.
     */
    private static boolean withinCredentialScope(SecurityContext subject, Resource resource) {
        String confinedTo = subject.restrictedToProject();
        if (confinedTo == null) {
            return true;
        }
        return switch (resource) {
            case Resource.Project r -> confinedTo.equals(r.projectName());
            case Resource.Document r -> confinedTo.equals(r.projectName());
            case Resource.Session r -> confinedTo.equals(r.projectName());
            case Resource.ThinkProcess r -> confinedTo.equals(r.projectName());
            case Resource.Setting r -> "project".equals(r.referenceType()) && confinedTo.equals(r.referenceId());
            case Resource.Tenant ignored -> false;
            case Resource.Team ignored -> false;
            case Resource.User ignored -> false;
            case Resource.InboxItem ignored -> false;
        };
    }
}
