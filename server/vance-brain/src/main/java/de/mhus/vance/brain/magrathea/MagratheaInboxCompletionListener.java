package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.inbox.InboxItemAnsweredEvent;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Subscribes to {@link InboxItemAnsweredEvent} and converts answers
 * for {@code workflow.gate}-tagged items into a {@link TaskCompletedEvent}
 * for the waiting Magrathea {@code gate_task} (plan §4.4, §6.4).
 *
 * <h3>Outcome mapping</h3>
 * <table>
 *   <tr><th>InboxType / AnswerOutcome</th><th>value</th><th>workflow outcome</th></tr>
 *   <tr><td>APPROVAL + DECIDED</td><td>{@code approved: true}</td><td>{@code approved}</td></tr>
 *   <tr><td>APPROVAL + DECIDED</td><td>{@code approved: false}</td><td>{@code rejected}</td></tr>
 *   <tr><td>DECISION + DECIDED</td><td>{@code chosen: "<option>"}</td><td>the chosen option</td></tr>
 *   <tr><td>FEEDBACK + DECIDED</td><td>any</td><td>{@code success} (free-form)</td></tr>
 *   <tr><td>any + INSUFFICIENT_INFO</td><td>—</td><td>{@code insufficient_info}</td></tr>
 *   <tr><td>any + UNDECIDABLE</td><td>—</td><td>{@code undecidable}</td></tr>
 * </table>
 *
 * <p>The {@code AnswerPayload.value} map is forwarded as the workflow
 * task's {@code output} so {@code storeAs:} captures the structured
 * answer.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaInboxCompletionListener {

    private final MagratheaTaskService taskService;
    private final MagratheaCompletionEventBus eventBus;
    private final ObjectMapper objectMapper;

    @EventListener
    public void onAnswered(InboxItemAnsweredEvent event) {
        InboxItemDocument item = event.item();
        if (!isWorkflowGate(item)) {
            return;
        }
        Optional<MagratheaTaskDocument> taskOpt = taskService.findByInboxItemId(item.getId());
        if (taskOpt.isEmpty()) {
            log.warn("Magrathea inbox listener: workflow.gate item '{}' has no linked task",
                    item.getId());
            return;
        }
        MagratheaTaskDocument task = taskOpt.get();
        AnswerPayload answer = item.getAnswer();
        if (answer == null) {
            log.warn("Magrathea inbox listener: item '{}' answered with null payload", item.getId());
            publish(task, "agent_error", null, "inbox answer payload missing");
            return;
        }

        String outcome = mapOutcome(item.getType(), answer);
        JsonNode output = answer.getValue() == null
                ? null
                : objectMapper.valueToTree(answer.getValue());
        String errorMessage = outcome.equals("insufficient_info") || outcome.equals("undecidable")
                ? answer.getReason()
                : null;

        publish(task, outcome, output, errorMessage);
    }

    private static boolean isWorkflowGate(InboxItemDocument item) {
        Map<String, Object> payload = item.getPayload();
        if (payload == null) return false;
        Object kind = payload.get("kind");
        return GateTaskExecutor.PAYLOAD_KIND.equals(kind);
    }

    private static String mapOutcome(InboxItemType type, AnswerPayload answer) {
        if (answer.getOutcome() == AnswerOutcome.INSUFFICIENT_INFO) return "insufficient_info";
        if (answer.getOutcome() == AnswerOutcome.UNDECIDABLE) return "undecidable";

        Map<String, Object> value = answer.getValue();
        switch (type) {
            case APPROVAL:
                Object approved = value == null ? null : value.get("approved");
                if (approved instanceof Boolean b) {
                    return b ? "approved" : "rejected";
                }
                // Lenient fallback — APPROVAL DECIDED without `approved` boolean
                // shouldn't happen, but treat as approved by default to avoid
                // wedging the run.
                return "approved";
            case DECISION:
                Object chosen = value == null ? null : value.get("chosen");
                if (chosen instanceof String s && !s.isBlank()) {
                    return s;
                }
                return "decided";
            case FEEDBACK:
                return "success";
            default:
                return "success";
        }
    }

    private void publish(MagratheaTaskDocument task, String outcome,
                         @Nullable JsonNode output, @Nullable String errorMessage) {
        eventBus.publish(new TaskCompletedEvent(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                task.getId(),
                task.getStateName(),
                MagratheaTaskType.GATE_TASK,
                outcome,
                output,
                errorMessage,
                0L,
                null));
        log.info("Magrathea gate_task completion task='{}' outcome='{}'",
                task.getId(), outcome);
    }
}
