package de.mhus.vance.brain.roles;

import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.brain.tools.eddie.AdminToolSupport;
import de.mhus.vance.shared.activity.EddieActivityKind;
import de.mhus.vance.shared.activity.EntityRef;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.role.RoleAdministrationService;
import de.mhus.vance.shared.role.RoleAssignmentException;
import de.mhus.vance.shared.role.request.RoleRequestOperation;
import de.mhus.vance.shared.role.request.RoleRequestService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Changing a role from a tool: applied directly when it lies within the
 * invoking user's own authority, raised as a role request otherwise.
 *
 * <p>Direct is safe because nobody can hand out more than they hold:
 * {@link RoleAdministrationService} refuses a tenant role above the actor's
 * own and any change to someone who holds a stronger one, and a project
 * membership needs ADMIN on that project. A change that fails on exactly
 * those grounds — {@link PermissionDeniedException} or
 * {@link RoleAssignmentException.Kind#BEYOND_AUTHORITY} — goes to someone who
 * may decide, as a {@code role_request_*} would. Every other refusal (last
 * tenant admin, service account, unknown name, external tenant roles) is the
 * caller's to fix and comes back as an error.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoleChangeSupport {

    private final RoleAdministrationService administration;
    private final RoleRequestSupport requestSupport;
    private final AdminToolSupport admin;

    /** Grants the role named in {@code target}. */
    public Map<String, Object> grant(
            ToolInvocationContext ctx, String toolName, RoleRequestService.Target target, @Nullable String reason) {
        SecurityContext actor = admin.actor(ctx, toolName);
        return change(ctx, RoleRequestOperation.GRANT, target, reason, () -> {
            if (target.scopeType() == RoleScopeType.TENANT) {
                administration.assignTenantRole(actor, ctx.tenantId(), target.subjectId(), requireTenantRole(target));
            } else {
                administration.assignProjectRole(
                        actor,
                        ctx.tenantId(),
                        target.scopeId(),
                        target.subjectType(),
                        target.subjectId(),
                        Objects.requireNonNull(target.projectRole(), "projectRole"));
            }
            return true;
        });
    }

    /**
     * Revokes: a project membership is removed, a tenant role falls back to
     * {@link TenantRole#USER} — the role everyone holds.
     */
    public Map<String, Object> revoke(
            ToolInvocationContext ctx, String toolName, RoleRequestService.Target target, @Nullable String reason) {
        SecurityContext actor = admin.actor(ctx, toolName);
        if (target.scopeType() == RoleScopeType.TENANT && target.subjectType() != RoleSubjectType.USER) {
            throw new ToolException("tenant roles are held by users only — a team has none to revoke");
        }
        return change(ctx, RoleRequestOperation.REVOKE, target, reason, () -> {
            if (target.scopeType() == RoleScopeType.TENANT) {
                administration.assignTenantRole(actor, ctx.tenantId(), target.subjectId(), TenantRole.USER);
                return true;
            }
            return administration.removeProjectRole(
                    actor, ctx.tenantId(), target.scopeId(), target.subjectType(), target.subjectId());
        });
    }

    private Map<String, Object> change(
            ToolInvocationContext ctx,
            RoleRequestOperation operation,
            RoleRequestService.Target target,
            @Nullable String reason,
            Change change) {
        boolean changed;
        try {
            changed = change.apply();
        } catch (PermissionDeniedException e) {
            return requested(ctx, operation, target, reason, e.getMessage());
        } catch (RoleAssignmentException e) {
            if (e.kind() == RoleAssignmentException.Kind.BEYOND_AUTHORITY) {
                return requested(ctx, operation, target, reason, e.getMessage());
            }
            throw new ToolException(e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", true);
        out.put("requested", false);
        out.put("operation", operation.name());
        out.put("scopeType", target.scopeType().name());
        out.put("scopeId", target.scopeId());
        out.put("subjectType", target.subjectType().name());
        out.put("subjectId", target.subjectId());
        String role = operation == RoleRequestOperation.GRANT
                ? RoleRequestSupport.roleName(target)
                : target.scopeType() == RoleScopeType.TENANT ? TenantRole.USER.name() : null;
        if (role != null) {
            out.put("role", role);
        }
        if (!changed) {
            out.put("note", "'" + target.subjectId() + "' had no membership on this project — nothing changed.");
            return out;
        }
        admin.record(ctx, EddieActivityKind.ROLE_CHANGED, summary(operation, target, role), refs(target));
        return out;
    }

    private Map<String, Object> requested(
            ToolInvocationContext ctx,
            RoleRequestOperation operation,
            RoleRequestService.Target target,
            @Nullable String reason,
            @Nullable String why) {
        log.debug(
                "role change beyond the authority of '{}' — raising a request: {} {}:{} on {}:{}",
                ctx.userId(),
                operation,
                target.subjectType(),
                target.subjectId(),
                target.scopeType(),
                target.scopeId());
        Map<String, Object> out = requestSupport.raise(ctx, operation, target, reason);
        out.put("beyondYourAuthority", why == null ? "this change is above your own role" : why);
        return out;
    }

    private static TenantRole requireTenantRole(RoleRequestService.Target target) {
        if (target.subjectType() != RoleSubjectType.USER) {
            throw new ToolException("tenant roles are held by users only — use a PROJECT scope for a team");
        }
        return Objects.requireNonNull(target.tenantRole(), "tenantRole");
    }

    private static String summary(
            RoleRequestOperation operation, RoleRequestService.Target target, @Nullable String role) {
        String scope =
                target.scopeType() == RoleScopeType.TENANT ? "in the tenant" : "on project `" + target.scopeId() + "`";
        return operation == RoleRequestOperation.GRANT
                ? "Granted role " + role + " to `" + target.subjectId() + "` " + scope
                : "Revoked the role of `" + target.subjectId() + "` " + scope;
    }

    private static List<EntityRef> refs(RoleRequestService.Target target) {
        EntityRef subject = target.subjectType() == RoleSubjectType.TEAM
                ? EntityRef.team(target.subjectId())
                : EntityRef.user(target.subjectId());
        return target.scopeType() == RoleScopeType.PROJECT
                ? List.of(subject, EntityRef.project(target.scopeId()))
                : List.of(subject);
    }

    @FunctionalInterface
    private interface Change {
        /** Applies the change; {@code false} when there was nothing to change. */
        boolean apply();
    }
}
