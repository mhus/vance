package de.mhus.vance.simpleauth.brain;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.api.inbox.EffectDescription;
import de.mhus.vance.api.inbox.EffectFact;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.inbox.InboxEffect;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.PermissionGrantService;
import de.mhus.vance.simpleauth.PermissionRequestDocument;
import de.mhus.vance.simpleauth.PermissionRequestOperation;
import de.mhus.vance.simpleauth.PermissionRequestService;
import de.mhus.vance.simpleauth.PermissionRequestStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Executes an approved {@link PermissionRequestDocument} — the one place
 * where a grant change may originate from an LLM-raised intent, and only
 * after a human said yes.
 *
 * <p>This is the whole point of the mechanism: the LLM writes the request,
 * the server holds it, and the mutation happens here under a SYSTEM
 * context. An agent that could act on its own "yes" would make the
 * approval decoration; here it cannot reach this code at all.
 *
 * <p>See {@code planning/permission-request-inbox.md} §6, §12 Phase 2.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PermissionRequestEffect implements InboxEffect {

    /** Registry key — persisted on inbox items, so it must stay stable. */
    public static final String EFFECT_TYPE = "permission-request";

    private final PermissionRequestService requests;
    private final PermissionGrantService grants;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;
    private final ProcessEventEmitter eventEmitter;

    @Override
    public String effectType() {
        return EFFECT_TYPE;
    }

    @Override
    public void onApproved(InboxItemDocument item, AnswerPayload answer) {
        PermissionRequestDocument request = load(item);
        if (request == null) {
            return;
        }
        String decidedBy = answer.getAnsweredBy();

        // Re-check the *responder*, not the requester, and do it now
        // rather than trusting the check made when the request was
        // raised: hours may have passed and rights may have changed.
        try {
            permissionService.enforce(
                    contextFactory.forToolSubject(request.getTenantId(), decidedBy),
                    scopeResource(request), Action.ADMIN);
        } catch (RuntimeException denied) {
            requests.markFailed(request.getId(), decidedBy,
                    "responder lacks ADMIN on the target scope");
            throw denied;
        }

        try {
            apply(request);
        } catch (RuntimeException e) {
            requests.markFailed(request.getId(), decidedBy, e.toString());
            throw e;
        }
        requests.markApproved(request.getId(), decidedBy);
        log.info("permission-request '{}' applied: {} {}:{} for {}:{} role={} approved by '{}'",
                request.getId(), request.getOperation(), request.getScopeType(),
                request.getScopeId(), request.getSubjectType(), request.getSubjectId(),
                request.getRole(), decidedBy);
        notifyRequester(request, true, decidedBy);
    }

    @Override
    public void onRejected(InboxItemDocument item, AnswerPayload answer) {
        PermissionRequestDocument request = load(item);
        if (request == null) {
            return;
        }
        // No ADMIN check here on purpose: rejecting changes no rights, and
        // whoever was routed the item — including a delegate — may decline
        // it. Only saying yes needs the authority.
        requests.markRejected(request.getId(), answer.getAnsweredBy());
        notifyRequester(request, false, answer.getAnsweredBy());
    }

    /**
     * Tells the process that raised the request how it was decided.
     *
     * <p>Without this an agent waiting on access has no way to learn it
     * arrived except by asking again — which means polling, which means
     * an LLM turn per attempt to discover "not yet". The moment of the
     * decision is known here exactly, so pushing beats any interval
     * someone would otherwise have to guess.
     *
     * <p>Best-effort: the requester may be gone, or on another pod that
     * cannot be reached. A lost notification costs a wait, not the
     * grant — which has already been written either way.
     */
    private void notifyRequester(
            PermissionRequestDocument request, boolean approved, String decidedBy) {
        String requester = request.getRequestedByProcessId();
        if (StringUtils.isBlank(requester)) {
            return;
        }
        String what = describeRequest(request);
        String summary = approved
                ? "Access request approved by " + decidedBy + ": " + what
                        + ". You can proceed."
                : "Access request declined by " + decidedBy + ": " + what
                        + ". Do not retry without new information.";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("permissionRequestId", request.getId());
        payload.put("approved", approved);
        payload.put("scopeType", request.getScopeType().name());
        payload.put("scopeId", request.getScopeId());
        payload.put("subjectId", request.getSubjectId());
        try {
            eventEmitter.notifyParent(requester, requester, ProcessEventType.SUMMARY,
                    summary, payload, null);
        } catch (RuntimeException e) {
            log.warn("Could not notify requester '{}' about permission-request '{}': {}",
                    requester, request.getId(), e.toString());
        }
    }

    private static String describeRequest(PermissionRequestDocument request) {
        String verb = request.getOperation() == PermissionRequestOperation.REVOKE
                ? "remove access of" : "grant "
                        + (request.getRole() == null ? "access" : request.getRole().name())
                        + " to";
        return verb + " '" + request.getSubjectId() + "' on "
                + request.getScopeType() + " '" + request.getScopeId() + "'";
    }

    /**
     * The facts the approver decides on — read from the request itself,
     * never from the item's body. The body only quotes what the agent
     * claimed; this is what would actually happen.
     */
    @Override
    public Optional<EffectDescription> describe(InboxItemDocument item) {
        String ref = item.getEffectRef();
        if (StringUtils.isBlank(ref)) {
            return Optional.empty();
        }
        return requests.findById(ref).map(request -> {
            List<EffectFact> facts = new ArrayList<>();
            facts.add(new EffectFact("Operation",
                    request.getOperation() == PermissionRequestOperation.REVOKE
                            ? "Remove access" : "Grant access"));
            facts.add(new EffectFact("Scope",
                    request.getScopeType() + " '" + request.getScopeId() + "'"));
            facts.add(new EffectFact("Subject",
                    request.getSubjectType() + " '" + request.getSubjectId() + "'"));
            if (request.getRole() != null) {
                facts.add(new EffectFact("Role", request.getRole().name()));
            }
            facts.add(new EffectFact("Requested by", request.getRequestedBy()));
            if (request.getDecidedBy() != null) {
                facts.add(new EffectFact("Decided by", request.getDecidedBy()));
            }
            return new EffectDescription(
                    request.getStatus().name(), request.getFailureReason(), List.copyOf(facts));
        });
    }

    private void apply(PermissionRequestDocument request) {
        if (request.getOperation() == PermissionRequestOperation.REVOKE) {
            grants.remove(request.getTenantId(), request.getScopeType(), request.getScopeId(),
                    request.getSubjectType(), request.getSubjectId());
            return;
        }
        if (request.getRole() == null) {
            throw new IllegalStateException(
                    "GRANT request '" + request.getId() + "' carries no role");
        }
        grants.set(request.getTenantId(), request.getScopeType(), request.getScopeId(),
                request.getSubjectType(), request.getSubjectId(), request.getRole(),
                "inbox-approval");
    }

    /**
     * Resolves the request behind an item, or {@code null} when there is
     * nothing left to do. Both misses are logged rather than thrown: a
     * vanished or already-decided request is a stale item, not a failure
     * the responder should see as an error.
     */
    private @org.jspecify.annotations.Nullable PermissionRequestDocument load(
            InboxItemDocument item) {
        String ref = item.getEffectRef();
        if (StringUtils.isBlank(ref)) {
            log.warn("Inbox item '{}' declares effect '{}' without an effectRef",
                    item.getId(), EFFECT_TYPE);
            return null;
        }
        Optional<PermissionRequestDocument> found = requests.findById(ref);
        if (found.isEmpty()) {
            log.warn("Permission request '{}' behind inbox item '{}' is gone", ref, item.getId());
            return null;
        }
        PermissionRequestDocument request = found.get();
        if (request.getStatus() != PermissionRequestStatus.PENDING) {
            log.info("Permission request '{}' is already {} — no action", ref, request.getStatus());
            return null;
        }
        return request;
    }

    private static Resource scopeResource(PermissionRequestDocument request) {
        return request.getScopeType() == GrantScopeType.TENANT
                ? new Resource.Tenant(request.getTenantId())
                : new Resource.Project(request.getTenantId(), request.getScopeId());
    }
}
