package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Subscribes to {@link ThinkProcessStatusChangedEvent} and publishes
 * a {@link TaskCompletedEvent} for any waiting Magrathea {@code agent_task}
 * (plan §4.0, §6.4). Looks up the linked task by
 * {@code subProcessId} so the listener doesn't care which engine ran
 * — Jeltz, Ford, Vogon, Marvin all funnel through here.
 *
 * <h3>Outcome mapping</h3>
 * <ul>
 *   <li>{@code closeReason == DONE} / {@code AUTO_CLOSE} →
 *       {@code success} for non-Jeltz engines; for Jeltz the last
 *       assistant message is parsed as the Jeltz wrapper
 *       ({@code success/attempts/data/error}) and the wrapper drives
 *       the outcome.</li>
 *   <li>{@code closeReason == STALE} → {@code technical_error}</li>
 *   <li>{@code closeReason == STOPPED} / {@code ARCHIVED} /
 *       {@code USER_DELETE} / {@code ABANDONED} → {@code cancelled}</li>
 *   <li>Missing close-reason on a CLOSED process → {@code technical_error}</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaThinkProcessCompletionListener {

    private static final String ENGINE_JELTZ = "jeltz";

    private final MagratheaTaskService taskService;
    private final MagratheaCompletionEventBus eventBus;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final ObjectMapper objectMapper;

    @EventListener
    public void onStatusChanged(ThinkProcessStatusChangedEvent event) {
        if (event.newStatus() != ThinkProcessStatus.CLOSED) {
            return;
        }
        Optional<MagratheaTaskDocument> taskOpt = taskService.findBySubProcessId(event.processId());
        if (taskOpt.isEmpty()) {
            // Not a Magrathea-spawned process — stay silent.
            return;
        }
        MagratheaTaskDocument task = taskOpt.get();

        Optional<ThinkProcessDocument> processOpt = thinkProcessService.findById(event.processId());
        if (processOpt.isEmpty()) {
            log.warn("Magrathea listener: ThinkProcess {} closed but document is gone — failing task {}",
                    event.processId(), task.getId());
            publish(task, "technical_error", null, "ThinkProcess document not found", 0L, null);
            return;
        }
        ThinkProcessDocument process = processOpt.get();
        CloseReason closeReason = process.getCloseReason();
        String engineName = process.getThinkEngine();

        long durationMs = computeDurationMs(process);

        // Categorise the closure first.
        if (closeReason == null) {
            log.warn("Magrathea listener: ThinkProcess {} closed without closeReason — technical_error",
                    event.processId());
            publish(task, "technical_error", null, "process closed without closeReason",
                    durationMs, null);
            return;
        }

        switch (closeReason) {
            case DONE:
            case AUTO_CLOSE:
                handleSuccessfulClose(task, process, engineName, durationMs);
                break;
            case STALE:
                publish(task, "technical_error", null,
                        "ThinkProcess STALE", durationMs, null);
                break;
            case STOPPED:
            case ARCHIVED:
            case USER_DELETE:
            case ABANDONED:
                publish(task, "cancelled", null,
                        "ThinkProcess closed with " + closeReason, durationMs, null);
                break;
            default:
                publish(task, "technical_error", null,
                        "Unhandled closeReason: " + closeReason, durationMs, null);
        }
    }

    private void handleSuccessfulClose(
            MagratheaTaskDocument task,
            ThinkProcessDocument process,
            String engineName,
            long durationMs) {
        List<ChatMessageDocument> history = chatMessageService.history(
                process.getTenantId(), process.getSessionId(), process.getId());
        Optional<ChatMessageDocument> lastAssistant = lastAssistant(history);

        if (ENGINE_JELTZ.equalsIgnoreCase(engineName)) {
            mapJeltzOutcome(task, lastAssistant, durationMs);
            return;
        }
        // Non-Jeltz engine: last assistant text is the output.
        JsonNode output = lastAssistant
                .map(ChatMessageDocument::getContent)
                .<JsonNode>map(objectMapper::valueToTree)
                .orElse(null);
        publish(task, TaskCompletedEvent.OUTCOME_SUCCESS, output, null, durationMs, null);
    }

    private void mapJeltzOutcome(
            MagratheaTaskDocument task,
            Optional<ChatMessageDocument> lastAssistant,
            long durationMs) {
        if (lastAssistant.isEmpty()) {
            publish(task, "agent_error", null,
                    "Jeltz closed without an assistant message", durationMs, null);
            return;
        }
        String body = lastAssistant.get().getContent();
        JsonNode wrapper = parseJsonOrNull(body);
        if (wrapper == null || !wrapper.isObject()) {
            publish(task, "agent_error", null,
                    "Jeltz assistant body is not a JSON object: " + truncate(body, 200),
                    durationMs, null);
            return;
        }
        JsonNode successNode = wrapper.get("success");
        if (successNode == null || !successNode.isBoolean()) {
            publish(task, "agent_error", null,
                    "Jeltz wrapper missing 'success' boolean", durationMs, null);
            return;
        }
        if (successNode.asBoolean()) {
            JsonNode data = wrapper.get("data");
            publish(task, TaskCompletedEvent.OUTCOME_SUCCESS, data, null, durationMs, null);
            return;
        }
        // Jeltz failure path — wrapper carries the error reason.
        String reason = wrapper.path("error").asString("schema_violation");
        String message = wrapper.path("message").asString("Jeltz returned success=false");
        JsonNode lastInvalid = wrapper.get("lastInvalid");
        publish(task, "agent_error", lastInvalid, "Jeltz " + reason + ": " + message,
                durationMs, null);
    }

    private @Nullable JsonNode parseJsonOrNull(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException ex) {
            return null;
        }
    }

    private static Optional<ChatMessageDocument> lastAssistant(List<ChatMessageDocument> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).getRole() == ChatRole.ASSISTANT) {
                return Optional.of(history.get(i));
            }
        }
        return Optional.empty();
    }

    private static long computeDurationMs(ThinkProcessDocument process) {
        if (process.getCreatedAt() == null) return 0L;
        java.time.Instant end = process.getUpdatedAt() != null
                ? process.getUpdatedAt() : java.time.Instant.now();
        return java.time.Duration.between(process.getCreatedAt(), end).toMillis();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private void publish(
            MagratheaTaskDocument task,
            String outcome,
            @Nullable JsonNode output,
            @Nullable String errorMessage,
            long durationMs,
            @Nullable String nextStateOverride) {
        eventBus.publish(new TaskCompletedEvent(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                task.getId(),
                task.getStateName(),
                MagratheaTaskType.AGENT_TASK,
                outcome,
                output,
                errorMessage,
                durationMs,
                nextStateOverride));

        // Audit trail in the lane log.
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("task", task.getId());
        info.put("outcome", outcome);
        info.put("durationMs", durationMs);
        log.info("Magrathea agent_task completion {}", info);
    }
}
