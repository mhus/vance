package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Tells the ThinkProcess that owns a run when the run needs something or
 * is finished — the mechanism by which a run that belongs to somebody gets
 * <em>represented</em>.
 *
 * <p>A run cannot speak for itself: it is not a process, it has no session
 * of its own to write into, and it has no idea whether anybody is looking.
 * Its owner does. So a blocked gate does not try to reach the person — it
 * tells the owner, and the owner (an engine, in a conversation) decides how
 * to raise it.
 *
 * <p><b>Signal, not payload.</b> What is sent is a nudge with a readable
 * summary, never the state of the run. The journal is the authority on what
 * a run is doing, and a message that carried a copy of it would be a second
 * answer that can go stale or be lost. The owner is expected to read the
 * run when it wakes.
 *
 * <p>Best-effort by construction: an owner that has since closed, a router
 * that is unavailable, a failed dispatch — none of these may hold up the
 * run. The gate is in the inbox either way, which is the path that does not
 * depend on anyone being represented.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaOwnerNotifier {

    /** {@code fromUser} on messages this notifier sends. */
    public static final String SENDER = "_magrathea";

    private final ObjectProvider<EngineMessageRouter> messageRouterProvider;

    /**
     * The run has stopped at something it needs a person for.
     *
     * @param summary one line the owner can show — the gate's title
     */
    public void runBlocked(
            @Nullable String ownerProcessId,
            String workflowRunId,
            String stateName,
            @Nullable String summary) {
        notify(ownerProcessId, workflowRunId, ProcessEventType.BLOCKED,
                "Workflow run is waiting at '" + stateName + "'"
                        + (summary == null || summary.isBlank() ? "" : ": " + summary));
    }

    /** The run reached a terminal state. */
    public void runTerminated(
            @Nullable String ownerProcessId,
            String workflowRunId,
            ProcessEventType eventType,
            @Nullable String summary) {
        notify(ownerProcessId, workflowRunId, eventType,
                summary == null || summary.isBlank()
                        ? "Workflow run " + eventType.name().toLowerCase(java.util.Locale.ROOT)
                        : summary);
    }

    private void notify(
            @Nullable String ownerProcessId,
            String workflowRunId,
            ProcessEventType eventType,
            String content) {
        if (ownerProcessId == null || ownerProcessId.isBlank()) {
            log.debug("Magrathea run {} has no owner to notify about {}",
                    workflowRunId, eventType);
            return;
        }

        EngineMessageRouter router = messageRouterProvider.getIfAvailable();
        if (router == null) {
            log.warn("Magrathea run {} could not notify owner {} — no message router",
                    workflowRunId, ownerProcessId);
            return;
        }
        try {
            boolean delivered = router.dispatch(
                    /* senderProcessId — a run is not a process */ null,
                    ownerProcessId,
                    PendingMessageDocument.builder()
                            .type(PendingMessageType.PROCESS_EVENT)
                            .at(Instant.now())
                            .fromUser(SENDER)
                            .eventType(eventType)
                            .content(content)
                            .build());
            if (delivered) {
                log.info("Magrathea run {} told its owner {} about {}",
                        workflowRunId, ownerProcessId, eventType);
            } else {
                log.warn("Magrathea run {} owner-notify to {} was not delivered ({})",
                        workflowRunId, ownerProcessId, eventType);
            }
        } catch (RuntimeException ex) {
            log.warn("Magrathea run {} owner-notify to {} failed: {}",
                    workflowRunId, ownerProcessId, ex.toString());
        }
    }
}
