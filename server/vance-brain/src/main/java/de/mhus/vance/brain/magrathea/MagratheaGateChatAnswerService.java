package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxItemStatus;
import de.mhus.vance.api.inbox.ResolvedBy;
import de.mhus.vance.api.magrathea.MagratheaTaskRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
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
 * one exactly-once guard ({@code InboxItemService.answer} ignores a second
 * answer to an already-answered item).
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
    private final InboxItemService inboxItemService;

    /**
     * Try to answer whatever gate {@code workflowRunId} is waiting at, using
     * {@code text} as the person's reply.
     *
     * @return true when the text was read as an answer and the gate was
     *         closed with it; false when there was no waiting gate or the
     *         text was not an answer — in both cases nothing changed
     */
    public boolean tryAnswer(
            String tenantId, String workflowRunId, String text, String answeredBy) {
        Optional<InboxItemDocument> maybeItem = findOpenGateItem(tenantId, workflowRunId);
        if (maybeItem.isEmpty()) return false;
        InboxItemDocument item = maybeItem.get();

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

    /** The still-pending gate item of this run, if it is sitting at one. */
    public Optional<InboxItemDocument> findOpenGateItem(String tenantId, String workflowRunId) {
        for (MagratheaTaskDocument task : taskService.findByRun(workflowRunId)) {
            if (task.getStatus() != MagratheaTaskStatus.CLAIMED) continue;
            if (task.getRunStatus() != MagratheaTaskRunStatus.WAITING_INBOX) continue;
            String itemId = task.getInboxItemId();
            if (itemId == null) continue;
            Optional<InboxItemDocument> item = inboxItemService.findById(tenantId, itemId);
            if (item.isPresent() && item.get().getStatus() == InboxItemStatus.PENDING) {
                return item;
            }
        }
        return Optional.empty();
    }
}
