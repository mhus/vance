package de.mhus.vance.simpleauth.brain;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.simpleauth.GrantRole;
import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.GrantSubjectType;
import de.mhus.vance.simpleauth.PermissionGrantDocument;
import de.mhus.vance.simpleauth.PermissionGrantService;
import de.mhus.vance.simpleauth.PermissionRequestDocument;
import de.mhus.vance.simpleauth.PermissionRequestOperation;
import de.mhus.vance.simpleauth.PermissionRequestService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shared path for the {@code permission_request_*} tools: writes the
 * request, routes an approval item to someone who can decide, and reports
 * back that nothing has happened yet.
 *
 * <p>Deliberately has no way to touch a grant. The tools call this; only
 * {@link PermissionRequestEffect} — reached exclusively through a human
 * answer — writes to {@link PermissionGrantService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class PermissionRequestSupport {

    private final PermissionRequestService requests;
    private final PermissionGrantService grants;
    private final MaximegalonService inboxItemService;

    /** Tool-facing variant: unpacks the invocation context and renders a result map. */
    Map<String, Object> raise(
            ToolInvocationContext ctx,
            PermissionRequestOperation operation,
            GrantScopeType scopeType, String scopeId,
            GrantSubjectType subjectType, String subjectId,
            @Nullable GrantRole role,
            @Nullable String reason) {

        Receipt receipt = raise(ctx.tenantId(),
                ctx.userId() == null ? "system" : ctx.userId(), ctx.processId(), ctx.sessionId(),
                operation, scopeType, scopeId, subjectType, subjectId, role, reason);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", true);
        out.put("applied", false);
        out.put("requestId", receipt.requestId());
        out.put("status", receipt.status());
        out.put("operation", operation.name());
        out.put("scopeType", scopeType.name());
        out.put("scopeId", scopeId);
        out.put("subjectType", subjectType.name());
        out.put("subjectId", subjectId);
        if (role != null) {
            out.put("role", role.name());
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
            out.put("note", "No administrator was found for this scope — "
                    + "the request cannot be decided and will expire.");
        }
        return out;
    }

    /**
     * Core path, free of any tool-invocation types so the
     * {@code PermissionRequestPort} implementation can reuse it.
     */
    Receipt raise(
            String tenantId, String requestedBy,
            @Nullable String processId, @Nullable String sessionId,
            PermissionRequestOperation operation,
            GrantScopeType scopeType, String scopeId,
            GrantSubjectType subjectType, String subjectId,
            @Nullable GrantRole role,
            @Nullable String reason) {

        PermissionRequestDocument request = requests.request(
                tenantId, operation, scopeType, scopeId, subjectType, subjectId,
                role, reason, requestedBy, processId);

        if (request.getInboxItemId() != null) {
            // Reused request (§11.3) — the original item is still open.
            return new Receipt(request.getId(), request.getInboxItemId(),
                    request.getStatus().name(), null, true);
        }

        Optional<String> decider = pickDecider(tenantId, scopeType, scopeId);
        if (decider.isEmpty()) {
            // No one can decide, so no item is created. The request lapses
            // on its own rather than silently becoming someone's problem.
            log.warn("Permission request '{}' has no ADMIN to decide on {}:{} in tenant '{}'",
                    request.getId(), scopeType, scopeId, tenantId);
            return new Receipt(request.getId(), null, request.getStatus().name(), null, false);
        }

        MaximegalonDocument item = inboxItemService.create(MaximegalonDocument.builder()
                .tenantId(tenantId)
                .originatorUserId(requestedBy)
                .assignedToUserId(decider.get())
                .originProcessId(processId)
                .originSessionId(sessionId)
                .type(MaximegalonType.APPROVAL)
                // Never LOW: a LOW item carrying a default auto-answers,
                // and a rights change must not be decided by a default.
                .criticality(Criticality.CRITICAL)
                .title(title(operation, scopeType, scopeId, subjectId, role))
                .body(body(reason))
                .effectType(PermissionRequestEffect.EFFECT_TYPE)
                .effectRef(request.getId())
                .requiresAction(true)
                .build());
        requests.attachInboxItem(request.getId(), item.getId());
        return new Receipt(request.getId(), item.getId(),
                request.getStatus().name(), decider.get(), false);
    }

    /** Internal result of raising a request. */
    record Receipt(
            String requestId, @Nullable String itemId,
            String status, @Nullable String decider, boolean reused) {
    }

    /**
     * Someone who may decide: an ADMIN on the scope itself, else a
     * tenant ADMIN (who inherits into projects). Sorted, so the choice is
     * reproducible rather than dependent on storage order; whoever gets it
     * can hand it on through the inbox's existing delegation.
     *
     * <p>Only USER subjects are considered — resolving a TEAM grant to its
     * members is a wider question than this needs to answer.
     */
    private Optional<String> pickDecider(
            String tenantId, GrantScopeType scopeType, String scopeId) {
        List<String> candidates = new ArrayList<>(
                adminUsers(tenantId, scopeType, scopeId));
        if (candidates.isEmpty() && scopeType == GrantScopeType.PROJECT) {
            candidates.addAll(adminUsers(tenantId, GrantScopeType.TENANT, tenantId));
        }
        return candidates.stream().sorted().findFirst();
    }

    private List<String> adminUsers(String tenantId, GrantScopeType scopeType, String scopeId) {
        List<String> users = new ArrayList<>();
        for (PermissionGrantDocument grant : grants.forScope(tenantId, scopeType, scopeId)) {
            if (grant.getRole() == GrantRole.ADMIN
                    && grant.getSubjectType() == GrantSubjectType.USER) {
                users.add(grant.getSubjectId());
            }
        }
        return users;
    }

    private static String title(
            PermissionRequestOperation operation, GrantScopeType scopeType, String scopeId,
            String subjectId, @Nullable GrantRole role) {
        return operation == PermissionRequestOperation.REVOKE
                ? "Revoke access of '" + subjectId + "' on " + scopeType + " '" + scopeId + "'"
                : "Grant " + role + " to '" + subjectId + "' on " + scopeType + " '" + scopeId + "'";
    }

    /**
     * The stated reason travels as a marked quote. It is written by an LLM
     * and may repeat injected text, so it must never read as the system's
     * own description of what will happen — the facts come from the
     * request document.
     */
    private static String body(@Nullable String reason) {
        if (reason == null || reason.isBlank()) {
            return "No reason was given.";
        }
        return "Stated reason (claim of the requesting agent, not verified):\n\n> "
                + reason.replace("\n", "\n> ");
    }
}
