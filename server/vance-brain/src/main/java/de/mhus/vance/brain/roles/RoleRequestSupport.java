package de.mhus.vance.brain.roles;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.role.RoleAssignmentDocument;
import de.mhus.vance.shared.role.RoleAssignmentService;
import de.mhus.vance.shared.role.request.RoleRequestDocument;
import de.mhus.vance.shared.role.request.RoleRequestOperation;
import de.mhus.vance.shared.role.request.RoleRequestService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Raising a role request: writes the request, routes an approval item to
 * someone who may decide, and reports back that nothing has happened yet. Used
 * by the {@code role_request_*} tools and by agent code that needs access
 * ({@code user_project_request}).
 *
 * <p>Deliberately has no way to change a role. Only {@link RoleRequestEffect} —
 * reached exclusively through a human answer — does.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoleRequestSupport {

    private final RoleRequestService requests;
    private final RoleAssignmentService assignments;
    private final MaximegalonService inboxItemService;

    /** Outcome of raising a request — enough to tell the user what happens next. */
    public record Receipt(
            String requestId,
            @Nullable String itemId,
            String status,
            @Nullable String decider,
            boolean reused) {}

    /** Tool-facing variant: unpacks the invocation context and renders a result map. */
    Map<String, Object> raise(
            ToolInvocationContext ctx,
            RoleRequestOperation operation,
            RoleRequestService.Target target,
            @Nullable String reason) {

        Receipt receipt = raise(
                ctx.tenantId(),
                ctx.userId() == null ? "system" : ctx.userId(),
                ctx.processId(),
                ctx.sessionId(),
                operation,
                target,
                reason);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", true);
        out.put("applied", false);
        out.put("requestId", receipt.requestId());
        out.put("status", receipt.status());
        out.put("operation", operation.name());
        out.put("scopeType", target.scopeType().name());
        out.put("scopeId", target.scopeId());
        out.put("subjectType", target.subjectType().name());
        out.put("subjectId", target.subjectId());
        String role = roleName(target);
        if (operation == RoleRequestOperation.GRANT && role != null) {
            out.put("role", role);
        }
        if (receipt.itemId() != null) {
            out.put("itemId", receipt.itemId());
        }
        if (receipt.decider() != null) {
            out.put("awaitingApprovalBy", receipt.decider());
        }
        if (receipt.reused()) {
            out.put("note", "An identical request is already awaiting approval.");
        } else if (receipt.itemId() == null) {
            out.put(
                    "note",
                    "No administrator was found for this scope — the request cannot be decided and will expire.");
        }
        return out;
    }

    /** Core path, free of tool-invocation types. */
    public Receipt raise(
            String tenantId,
            String requestedBy,
            @Nullable String processId,
            @Nullable String sessionId,
            RoleRequestOperation operation,
            RoleRequestService.Target target,
            @Nullable String reason) {

        RoleRequestDocument request = requests.request(tenantId, operation, target, reason, requestedBy, processId);
        String requestId = Objects.requireNonNull(request.getId());

        if (request.getInboxItemId() != null) {
            // Reused request (§11.3) — the original item is still open.
            return new Receipt(
                    requestId, request.getInboxItemId(), request.getStatus().name(), null, true);
        }

        Optional<String> decider = pickDecider(tenantId, target);
        if (decider.isEmpty()) {
            // No one can decide, so no item is created. The request lapses on
            // its own rather than silently becoming someone's problem.
            log.warn(
                    "Role request '{}' has nobody to decide on {}:{} in tenant '{}'",
                    requestId,
                    target.scopeType(),
                    target.scopeId(),
                    tenantId);
            return new Receipt(requestId, null, request.getStatus().name(), null, false);
        }

        MaximegalonDocument item = inboxItemService.create(MaximegalonDocument.builder()
                .tenantId(tenantId)
                .originatorUserId(requestedBy)
                .assignedToUserId(decider.get())
                .originProcessId(processId)
                .originSessionId(sessionId)
                .type(MaximegalonType.APPROVAL)
                // Never LOW: a LOW item carrying a default auto-answers, and a
                // rights change must not be decided by a default.
                .criticality(Criticality.CRITICAL)
                .title(title(operation, target))
                .body(body(reason))
                .effectType(RoleRequestEffect.EFFECT_TYPE)
                .effectRef(requestId)
                .requiresAction(true)
                .build());
        requests.attachInboxItem(requestId, Objects.requireNonNull(item.getId()));
        return new Receipt(requestId, item.getId(), request.getStatus().name(), decider.get(), false);
    }

    /**
     * Someone who may decide. For a project: an ADMIN of the project, else a
     * tenant ADMIN (who administers every project). For the tenant: a user whose
     * tenant role covers the requested one — at least ADMIN, TENANT_ADMIN when
     * TENANT_ADMIN is asked for or the subject is a TENANT_ADMIN today. Sorted, so the choice is reproducible; whoever
     * gets it can hand it on through the inbox's delegation.
     *
     * <p>Only users are considered — resolving a team membership to its members
     * is a wider question than this needs to answer.
     */
    private Optional<String> pickDecider(String tenantId, RoleRequestService.Target target) {
        List<String> candidates = new ArrayList<>();
        if (target.scopeType() == RoleScopeType.PROJECT) {
            for (RoleAssignmentDocument a : assignments.forScope(tenantId, RoleScopeType.PROJECT, target.scopeId())) {
                if (a.getSubjectType() == RoleSubjectType.USER && a.getProjectRole() == ProjectRole.ADMIN) {
                    candidates.add(a.getSubjectId());
                }
            }
        }
        if (candidates.isEmpty()) {
            if (!needsTenantAdmin(tenantId, target)) {
                candidates.addAll(assignments.usersWithTenantRole(tenantId, TenantRole.ADMIN));
            }
            candidates.addAll(assignments.usersWithTenantRole(tenantId, TenantRole.TENANT_ADMIN));
        }
        return candidates.stream().sorted().findFirst();
    }

    /**
     * Whether only a TENANT_ADMIN can carry the tenant-scope request out: when
     * TENANT_ADMIN is asked for, and when the subject holds it today — a revoke
     * or demotion of a tenant admin reaches above an ADMIN's own role, and the
     * effect would refuse an ADMIN's approval.
     */
    private boolean needsTenantAdmin(String tenantId, RoleRequestService.Target target) {
        if (target.tenantRole() == TenantRole.TENANT_ADMIN) {
            return true;
        }
        return target.scopeType() == RoleScopeType.TENANT
                && target.subjectType() == RoleSubjectType.USER
                && assignments.tenantRoleOf(tenantId, target.subjectId()) == TenantRole.TENANT_ADMIN;
    }

    static @Nullable String roleName(RoleRequestService.Target target) {
        if (target.tenantRole() != null) {
            return target.tenantRole().name();
        }
        return target.projectRole() == null ? null : target.projectRole().name();
    }

    private static String title(RoleRequestOperation operation, RoleRequestService.Target target) {
        String scope = target.scopeType() + " '" + target.scopeId() + "'";
        return operation == RoleRequestOperation.REVOKE
                ? "Revoke the role of '" + target.subjectId() + "' on " + scope
                : "Grant " + roleName(target) + " to '" + target.subjectId() + "' on " + scope;
    }

    /**
     * The stated reason travels as a marked quote. It is written by an LLM and
     * may repeat injected text, so it must never read as the system's own
     * description of what will happen — the facts come from the request
     * document.
     */
    private static String body(@Nullable String reason) {
        if (reason == null || reason.isBlank()) {
            return "No reason was given.";
        }
        return "Stated reason (claim of the requesting agent, not verified):\n\n> " + reason.replace("\n", "\n> ");
    }
}
