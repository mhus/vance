package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.ResolvedBy;
import de.mhus.vance.api.magrathea.MagratheaTaskRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The second way to answer a gate: by saying it, in the conversation the
 * run's owner is part of.
 *
 * <p>The first way — the inbox form — is always available and is what the
 * gate actually waits on. This service does not add a competing completion
 * path; it <b>writes the same inbox answer</b> the form would have written,
 * so a chat reply and a click end up in one place, with one audit trail and
 * one exactly-once guard ({@code MaximegalonService.answer} ignores a second
 * answer to an already-answered item).
 *
 * <p>Same answer means same gate in front of it: whoever speaks must be
 * allowed to answer <em>that item</em> ({@code Resource.InboxItem} +
 * {@code WRITE}, the check both form surfaces run). An item assigned
 * elsewhere stays open no matter what is said in the conversation.
 *
 * <p>An utterance that cannot be read as an answer changes nothing. The
 * gate stays open, the question can be repeated, and the person can still
 * use the form. Being unable to interpret a sentence is a normal outcome
 * here, not an error.
 */
@Service
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaGateChatAnswerService {

    private final MagratheaTaskService taskService;
    private final MaximegalonService inboxItemService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory securityContexts;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;

    /**
     * Try to answer whatever gate {@code workflowRunId} is waiting at, using
     * {@code text} as the person's reply.
     *
     * @return true when the text was read as an answer and the gate was
     *         closed with it; false when there was no waiting gate, the
     *         speaker may not answer it, or the text was not an answer — in
     *         every case nothing changed
     */
    public boolean tryAnswer(
            String tenantId, String workflowRunId, String text, String answeredBy) {
        Optional<MaximegalonDocument> maybeItem = findOpenGateItem(tenantId, workflowRunId);
        if (maybeItem.isEmpty()) return false;
        MaximegalonDocument item = maybeItem.get();

        if (!mayAnswer(tenantId, item, answeredBy)) {
            log.info("Magrathea run {} gate '{}' — '{}' may not answer it; gate stays open",
                    workflowRunId, item.getId(), answeredBy);
            return false;
        }

        List<String> options = GateChatAnswerParser.optionsOf(item.getPayload());
        Optional<AnswerPayload> parsed =
                GateChatAnswerParser.parse(item.getType(), text, options, answeredBy);
        if (parsed.isEmpty()) {
            log.debug("Magrathea run {} gate '{}' — chat text was not readable as an answer",
                    workflowRunId, item.getId());
            return false;
        }

        try {
            inboxItemService.answer(tenantId, item.getId(), parsed.get(), ResolvedBy.USER);
        } catch (RuntimeException ex) {
            log.warn("Magrathea run {} gate '{}' — answering from chat failed: {}",
                    workflowRunId, item.getId(), ex.toString());
            return false;
        }
        log.info("Magrathea run {} gate '{}' answered from chat by '{}'",
                workflowRunId, item.getId(), answeredBy);
        return true;
    }

    /**
     * The gate in front of the gate: may this person answer this item at all?
     *
     * <p>The form route asks it at both surfaces ({@code InboxAnswerHandler},
     * {@code InboxController}) and {@code MaximegalonService.answer} asks
     * nothing — so writing "the same answer the form would have written"
     * without this check writes it past the one rule the form has. An item
     * assigned to a team or to somebody else must not close because a
     * bystander in the conversation said "ok".
     *
     * <p>Asked through {@code PermissionService} rather than by comparing the
     * assignee here: who may act on an inbox item is the resolver's rule (R5
     * — assignee or a shared team), and an enforcement point that reimplements
     * it is one that drifts from it.
     *
     * <p>A blank or system speaker is refused outright. It would otherwise
     * resolve to the SYSTEM subject, which passes every check — and a gate
     * that a scheduler-authored line can close is not a gate.
     */
    private boolean mayAnswer(String tenantId, MaximegalonDocument item, String answeredBy) {
        if (answeredBy.isBlank()
                || de.mhus.vance.shared.session.SessionService.SYSTEM_OWNER.equals(answeredBy)) {
            return false;
        }
        return permissionService.check(
                securityContexts.forToolSubject(tenantId, answeredBy),
                new de.mhus.vance.shared.permission.Resource.InboxItem(
                        item.getTenantId() == null ? tenantId : item.getTenantId(),
                        item.getId() == null ? "" : item.getId(),
                        item.getAssignedToUserId() == null ? "" : item.getAssignedToUserId()),
                de.mhus.vance.shared.permission.Action.WRITE);
    }

    /** The still-pending gate item of this run, if it is sitting at one. */
    public Optional<MaximegalonDocument> findOpenGateItem(String tenantId, String workflowRunId) {
        for (MagratheaTaskDocument task : taskService.findByRun(workflowRunId)) {
            if (task.getStatus() != MagratheaTaskStatus.CLAIMED) continue;
            if (task.getRunStatus() != MagratheaTaskRunStatus.WAITING_INBOX) continue;
            String itemId = task.getInboxItemId();
            if (itemId == null) continue;
            Optional<MaximegalonDocument> item = inboxItemService.findById(tenantId, itemId);
            if (item.isPresent() && item.get().getStatus() == MaximegalonStatus.PENDING) {
                return item;
            }
        }
        return Optional.empty();
    }
}
