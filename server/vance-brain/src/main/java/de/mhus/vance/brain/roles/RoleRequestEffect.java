package de.mhus.vance.brain.roles;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.EffectDescription;
import de.mhus.vance.api.inbox.EffectFact;
import de.mhus.vance.api.inbox.ResolvedBy;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.inbox.InboxEffect;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.role.RoleAdministrationService;
import de.mhus.vance.shared.role.request.RoleRequestDocument;
import de.mhus.vance.shared.role.request.RoleRequestOperation;
import de.mhus.vance.shared.role.request.RoleRequestService;
import de.mhus.vance.shared.role.request.RoleRequestStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Carries out an approved {@link RoleRequestDocument} — the one place where a
 * role change may originate from an agent-raised intent, and only after a human
 * said yes.
 *
 * <p>The change runs as the <em>responder</em>, through
 * {@link RoleAdministrationService}: their authority over the scope and every
 * assignment rule are checked now, not when the request was raised — hours may
 * have passed and rights may have changed. An agent that could act on its own
 * "yes" would make the approval decoration; here it cannot reach this code.
 *
 * <p>See {@code planning/permission-request-inbox.md} §6, §12.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoleRequestEffect implements InboxEffect {

    /** Registry key — persisted on inbox items, so it must stay stable. */
    public static final String EFFECT_TYPE = "permission-request";

    private final RoleRequestService requests;
    private final RoleAdministrationService administration;
    private final SecurityContextFactory contextFactory;
    private final ProcessEventEmitter eventEmitter;

    @Override
    public String effectType() {
        return EFFECT_TYPE;
    }

    /**
     * This effect pushes its own message to the requester (see
     * {@link #notifyRequester}), which names the role and says the work can
     * continue. The generic answer route would add a second arrival saying the
     * same thing less usefully.
     */
    @Override
    public boolean notifiesOrigin() {
        return true;
    }

    @Override
    public void onApproved(MaximegalonDocument item, AnswerPayload answer) {
        RoleRequestDocument request = load(item);
        if (request == null) {
            return;
        }
        String requestId = Objects.requireNonNull(request.getId());
        String decidedBy = answer.getAnsweredBy();
        if (item.getResolvedBy() != ResolvedBy.USER || StringUtils.isBlank(decidedBy)) {
            // Only a human yes grants a role. forToolSubject would turn a
            // missing decider into the SYSTEM context, which holds every
            // right, and an AUTO_* answer names no user at all.
            String reason = "approval was not given by a user (resolvedBy=" + item.getResolvedBy() + ")";
            requests.markFailed(requestId, decidedBy, reason);
            throw new IllegalStateException("role-request '" + requestId + "': " + reason);
        }
        try {
            apply(request, contextFactory.forToolSubject(request.getTenantId(), decidedBy));
        } catch (RuntimeException e) {
            requests.markFailed(requestId, decidedBy, e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }
        requests.markApproved(requestId, decidedBy);
        log.info(
                "role-request '{}' applied: {} {}:{} for {}:{} role={} approved by '{}'",
                requestId,
                request.getOperation(),
                request.getScopeType(),
                request.getScopeId(),
                request.getSubjectType(),
                request.getSubjectId(),
                request.roleName(),
                decidedBy);
        notifyRequester(request, true, decidedBy);
    }

    @Override
    public void onRejected(MaximegalonDocument item, AnswerPayload answer) {
        RoleRequestDocument request = load(item);
        if (request == null) {
            return;
        }
        // No authority check here on purpose: rejecting changes no rights, and
        // whoever was routed the item — including a delegate — may decline it.
        // Only saying yes needs the authority.
        requests.markRejected(Objects.requireNonNull(request.getId()), answer.getAnsweredBy());
        notifyRequester(request, false, answer.getAnsweredBy());
    }

    private void apply(RoleRequestDocument request, SecurityContext responder) {
        String tenantId = request.getTenantId();
        if (request.getScopeType() == RoleScopeType.TENANT) {
            TenantRole role = request.getOperation() == RoleRequestOperation.REVOKE
                    ? TenantRole.USER
                    : Objects.requireNonNull(request.getTenantRole(), "GRANT request carries no tenant role");
            administration.assignTenantRole(responder, tenantId, request.getSubjectId(), role);
            return;
        }
        if (request.getOperation() == RoleRequestOperation.REVOKE) {
            administration.removeProjectRole(
                    responder, tenantId, request.getScopeId(), request.getSubjectType(), request.getSubjectId());
            return;
        }
        administration.assignProjectRole(
                responder,
                tenantId,
                request.getScopeId(),
                request.getSubjectType(),
                request.getSubjectId(),
                Objects.requireNonNull(request.getProjectRole(), "GRANT request carries no project role"));
    }

    /**
     * Tells the process that raised the request how it was decided.
     *
     * <p>Without this an agent waiting on access has no way to learn it arrived
     * except by asking again — polling, an LLM turn per attempt to discover "not
     * yet". The moment of the decision is known here exactly.
     *
     * <p>Best-effort: the requester may be gone, or on another pod that cannot be
     * reached. A lost notification costs a wait, not the role — which has
     * already been written either way.
     */
    private void notifyRequester(RoleRequestDocument request, boolean approved, String decidedBy) {
        String requester = request.getRequestedByProcessId();
        if (StringUtils.isBlank(requester)) {
            return;
        }
        String what = describeRequest(request);
        String summary = approved
                ? "Access request approved by " + decidedBy + ": " + what + ". You can proceed."
                : "Access request declined by " + decidedBy + ": " + what + ". Do not retry without new information.";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("permissionRequestId", request.getId());
        payload.put("approved", approved);
        payload.put("scopeType", request.getScopeType().name());
        payload.put("scopeId", request.getScopeId());
        payload.put("subjectId", request.getSubjectId());
        try {
            eventEmitter.notifyParent(requester, requester, ProcessEventType.SUMMARY, summary, payload, null);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not notify requester '{}' about role-request '{}': {}",
                    requester,
                    request.getId(),
                    e.toString());
        }
    }

    private static String describeRequest(RoleRequestDocument request) {
        String verb = request.getOperation() == RoleRequestOperation.REVOKE
                ? "remove the role of"
                : "grant " + (request.roleName() == null ? "access" : request.roleName()) + " to";
        return verb + " '" + request.getSubjectId() + "' on " + request.getScopeType() + " '" + request.getScopeId()
                + "'";
    }

    /**
     * The facts the approver decides on — read from the request itself, never
     * from the item's body. The body only quotes what the agent claimed; this
     * is what would actually happen.
     */
    @Override
    public Optional<EffectDescription> describe(MaximegalonDocument item) {
        String ref = item.getEffectRef();
        if (StringUtils.isBlank(ref)) {
            return Optional.empty();
        }
        return requests.findById(ref).map(request -> {
            List<EffectFact> facts = new ArrayList<>();
            facts.add(new EffectFact(
                    "Operation",
                    request.getOperation() == RoleRequestOperation.REVOKE ? "Remove access" : "Grant access"));
            facts.add(new EffectFact("Scope", request.getScopeType() + " '" + request.getScopeId() + "'"));
            facts.add(new EffectFact("Subject", request.getSubjectType() + " '" + request.getSubjectId() + "'"));
            if (request.roleName() != null) {
                facts.add(new EffectFact("Role", request.roleName()));
            }
            facts.add(new EffectFact("Requested by", request.getRequestedBy()));
            if (request.getDecidedBy() != null) {
                facts.add(new EffectFact("Decided by", request.getDecidedBy()));
            }
            return new EffectDescription(request.getStatus().name(), request.getFailureReason(), List.copyOf(facts));
        });
    }

    /**
     * Resolves the request behind an item, or {@code null} when there is nothing
     * left to do. Both misses are logged rather than thrown: a vanished or
     * already-decided request is a stale item, not a failure the responder
     * should see as an error.
     */
    private @Nullable RoleRequestDocument load(MaximegalonDocument item) {
        String ref = item.getEffectRef();
        if (StringUtils.isBlank(ref)) {
            log.warn("Inbox item '{}' declares effect '{}' without an effectRef", item.getId(), EFFECT_TYPE);
            return null;
        }
        Optional<RoleRequestDocument> found = requests.findById(ref);
        if (found.isEmpty()) {
            log.warn("Role request '{}' behind inbox item '{}' is gone", ref, item.getId());
            return null;
        }
        RoleRequestDocument request = found.get();
        if (request.getStatus() != RoleRequestStatus.PENDING) {
            log.info("Role request '{}' is already {} — no action", ref, request.getStatus());
            return null;
        }
        return request;
    }
}
