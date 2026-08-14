package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
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
 *
 * <h3>Turn-end mapping</h3>
 * Engines other than Jeltz never close themselves, so the listener also
 * completes a task when the agent merely finished its turn — see
 * {@link #completeAfterTurn}. {@code RUNNING → IDLE} is {@code success},
 * {@code RUNNING → BLOCKED} is {@code needs_input}.
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

    /**
     * Outcome for an agent that ended its turn with a question. Not an
     * error kind — it routes through {@code on:}, typically to a
     * {@code gate_task} that puts the question to a human.
     */
    public static final String OUTCOME_NEEDS_INPUT = "needs_input";

    private final MagratheaTaskService taskService;
    private final MagratheaCompletionEventBus eventBus;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final ObjectMapper objectMapper;
    private final MagratheaJournalService journalService;
    private final org.springframework.beans.factory.ObjectProvider<EngineMessageRouter>
            messageRouterProvider;

    @EventListener
    public void onStatusChanged(ThinkProcessStatusChangedEvent event) {
        boolean closed = event.newStatus() == ThinkProcessStatus.CLOSED;
        boolean turnEnded = event.priorStatus() == ThinkProcessStatus.RUNNING
                && (event.newStatus() == ThinkProcessStatus.IDLE
                    || event.newStatus() == ThinkProcessStatus.BLOCKED);
        if (!closed && !turnEnded) {
            return;
        }
        Optional<MagratheaTaskDocument> taskOpt = taskService.findBySubProcessId(event.processId());
        if (taskOpt.isEmpty()) {
            // Not a Magrathea-spawned process — stay silent.
            return;
        }
        if (closed) {
            reconcile(taskOpt.get(), event.processId());
        } else {
            completeAfterTurn(taskOpt.get(), event.processId(), event.newStatus());
        }
    }

    /**
     * Finish the task when the agent finished its turn — the completion
     * criterion for every engine that does not end itself.
     *
     * <p>Only Jeltz closes on its own; it is single-shot by construction.
     * Ford, Vogon, Marvin and Arthur end a turn at {@code IDLE} or
     * {@code BLOCKED} and wait for the next message, which inside a
     * workflow never comes. Waiting for {@code CLOSED} therefore asks the
     * agent a question it cannot answer: it does not know the step is
     * over — the task does.
     *
     * <p>The prior status is what makes this decidable. {@code INIT →
     * IDLE} is an engine that has started and not yet worked;
     * {@code RUNNING → IDLE} is a finished turn. Without
     * {@link ThinkProcessStatusChangedEvent#priorStatus} the two would be
     * indistinguishable and every spawn would complete instantly.
     *
     * <p>The end status carries how it went, so it maps straight onto an
     * outcome the workflow can route: {@code IDLE} = done,
     * {@code BLOCKED} = the agent asked something back and the run should
     * take that to a human ({@code needs_input} → a {@code gate_task}).
     *
     * <p>The link is dropped before closing so the resulting
     * {@code CLOSED} event finds no task and stays quiet — otherwise it
     * would publish a second completion behind this one.
     */
    private void completeAfterTurn(
            MagratheaTaskDocument task, String processId, ThinkProcessStatus endStatus) {
        Optional<ThinkProcessDocument> processOpt = thinkProcessService.findById(processId);
        if (processOpt.isEmpty()) {
            log.warn("Magrathea listener: ThinkProcess {} ended a turn but document is gone "
                    + "— failing task {}", processId, task.getId());
            publish(task, "technical_error", null, "ThinkProcess document not found", 0L, null);
            return;
        }
        ThinkProcessDocument process = processOpt.get();
        long durationMs = computeDurationMs(process);
        JsonNode output = lastAssistant(chatMessageService.history(
                        process.getTenantId(), process.getSessionId(), process.getId()))
                .map(ChatMessageDocument::getContent)
                .<JsonNode>map(objectMapper::valueToTree)
                .orElse(null);
        String answerText = output != null && output.isString() ? output.asString() : null;

        boolean asked = endStatus == ThinkProcessStatus.BLOCKED;

        // Before ending the step: if it asked for a judgement, read the
        // answer as one. A mis-shaped answer sends the question back
        // instead of ending anything — see askAgain.
        if (!asked) {
            Optional<AgentOutcomeRefiner.Result> refined = refineJudgement(task, process);
            if (refined.isPresent()) {
                switch (refined.get()) {
                    case AgentOutcomeRefiner.Decided d -> {
                        taskService.unlinkSubProcess(task.getId());
                        closeQuietly(processId, CloseReason.DONE);
                        publish(task, d.outcome(), d.output(), null, durationMs, null);
                        return;
                    }
                    case AgentOutcomeRefiner.NeedsCorrection c -> {
                        if (askAgain(task, process, c.hint())) return;
                        // Budget spent — the model could not produce the
                        // shape, which the plan can route on.
                        taskService.unlinkSubProcess(task.getId());
                        closeQuietly(processId, CloseReason.DONE);
                        publish(task, "agent_error", output,
                                "agent did not answer in the requested shape: " + c.hint(),
                                durationMs, null);
                        return;
                    }
                }
            }
        }

        taskService.unlinkSubProcess(task.getId());
        try {
            thinkProcessService.closeProcess(
                    processId, asked ? CloseReason.INCOMPLETE : CloseReason.DONE);
        } catch (RuntimeException ex) {
            log.warn("Magrathea listener: could not close finished agent process '{}': {}",
                    processId, ex.toString());
        }
        publish(task,
                asked ? OUTCOME_NEEDS_INPUT : TaskCompletedEvent.OUTCOME_SUCCESS,
                output,
                asked ? "agent ended its turn awaiting input" : null,
                durationMs, null);
    }

    /**
     * Read the finished agent's answer as the judgement its state asked
     * for, or empty when the state asked for none.
     *
     * <p>The state spec comes from the run's frozen definition, not from
     * anything the agent knows — the plan decides what shape it wanted, and
     * it decided that before the run began.
     */
    private Optional<AgentOutcomeRefiner.Result> refineJudgement(
            MagratheaTaskDocument task, ThinkProcessDocument process) {
        Optional<MagratheaStateSpec> stateOpt = frozenState(task);
        if (stateOpt.isEmpty()) return Optional.empty();

        Optional<AgentOutcomeRefiner.Judgement> judgement;
        try {
            judgement = AgentOutcomeRefiner.judgementOf(stateOpt.get());
        } catch (RuntimeException ex) {
            // A malformed decide:/score: block is an authoring error. Say so
            // once, and let the step end normally rather than wedging the run.
            log.warn("Magrathea agent_task '{}' has an invalid judgement block: {}",
                    task.getStateName(), ex.getMessage());
            return Optional.empty();
        }
        if (judgement.isEmpty()) return Optional.empty();

        String answer = lastAssistant(chatMessageService.history(
                        process.getTenantId(), process.getSessionId(), process.getId()))
                .map(ChatMessageDocument::getContent)
                .orElse(null);
        return Optional.of(
                AgentOutcomeRefiner.refine(judgement.get(), answer, objectMapper));
    }

    /** The state spec as frozen into this run's definition. */
    private Optional<MagratheaStateSpec> frozenState(MagratheaTaskDocument task) {
        try {
            return journalService.readLast(task.getTenantId(), task.getProjectId(),
                            task.getWorkflowRunId(), StartRecord.class)
                    .map(start -> MagratheaWorkflowLoader.parseYaml(
                            start.getWorkflowName(), start.getDefinitionYaml()))
                    .map(wf -> wf.states().get(task.getStateName()));
        } catch (RuntimeException ex) {
            log.warn("Magrathea agent_task '{}' — could not re-read the frozen state: {}",
                    task.getStateName(), ex.toString());
            return Optional.empty();
        }
    }

    /**
     * Put the question back to the still-running agent.
     *
     * <p>The process is deliberately neither unlinked nor closed: it is not
     * finished, it misunderstood. Re-asking keeps the same process, so the
     * model sees its own previous attempt and what was wrong with it —
     * which is the whole reason this is a correction and not a retry.
     *
     * @return true when a re-ask was dispatched and this step is still
     *         running; false when the budget is spent
     */
    private boolean askAgain(
            MagratheaTaskDocument task, ThinkProcessDocument process, String hint) {
        Optional<AgentOutcomeRefiner.Judgement> judgement = frozenState(task)
                .flatMap(s -> {
                    try {
                        return AgentOutcomeRefiner.judgementOf(s);
                    } catch (RuntimeException ex) {
                        return Optional.empty();
                    }
                });
        if (judgement.isEmpty()) return false;

        int used = taskService.incrementCorrectionCount(task.getId());
        if (used > judgement.get().maxCorrections()) {
            log.info("Magrathea agent_task '{}' exhausted its {} correction(s)",
                    task.getStateName(), judgement.get().maxCorrections());
            return false;
        }

        EngineMessageRouter router = messageRouterProvider.getIfAvailable();
        if (router == null) {
            log.warn("Magrathea agent_task '{}' cannot re-ask — no message router",
                    task.getStateName());
            return false;
        }
        boolean delivered = router.dispatch(
                /* senderProcessId — a run is not a process */ null,
                process.getId(),
                de.mhus.vance.shared.thinkprocess.PendingMessageDocument.builder()
                        .type(de.mhus.vance.shared.thinkprocess.PendingMessageType.USER_CHAT_INPUT)
                        .at(java.time.Instant.now())
                        .fromUser(MagratheaOwnerNotifier.SENDER)
                        .content(hint)
                        .build());
        if (!delivered) {
            log.warn("Magrathea agent_task '{}' re-ask was not delivered to process {}",
                    task.getStateName(), process.getId());
            return false;
        }
        log.info("Magrathea agent_task '{}' re-asked the agent (correction {}/{})",
                task.getStateName(), used, judgement.get().maxCorrections());
        return true;
    }

    private void closeQuietly(String processId, CloseReason reason) {
        try {
            thinkProcessService.closeProcess(processId, reason);
        } catch (RuntimeException ex) {
            log.warn("Magrathea listener: could not close agent process '{}': {}",
                    processId, ex.toString());
        }
    }

    /**
     * Map the terminal status of the task's linked {@code ThinkProcess}
     * onto the waiting task and publish the {@link TaskCompletedEvent}.
     * Shared by the live {@link #onStatusChanged} listener and the
     * crash-recovery scanner (code-review Phase 2 HIGH #4): the
     * status-changed event is an in-memory Spring event, so a pod crash
     * between event-fire and lane dispatch leaves the task stuck in
     * {@code WAITING_SUBPROCESS} forever. The scanner calls this to
     * re-drive the exact same outcome mapping from the persisted process
     * status. The completion dispatcher is idempotent
     * ({@code appendIfAbsent} on the result record), so a re-drive that
     * races the live path is harmless.
     *
     * @return {@code true} when the process was terminal and a completion
     *         was published; {@code false} when the process is still
     *         running (scanner no-op).
     */
    public boolean reconcile(MagratheaTaskDocument task, String processId) {
        Optional<ThinkProcessDocument> processOpt = thinkProcessService.findById(processId);
        if (processOpt.isEmpty()) {
            log.warn("Magrathea listener: ThinkProcess {} closed but document is gone — failing task {}",
                    processId, task.getId());
            publish(task, "technical_error", null, "ThinkProcess document not found", 0L, null);
            return true;
        }
        ThinkProcessDocument process = processOpt.get();
        if (process.getStatus() != ThinkProcessStatus.CLOSED) {
            // Still running — the completion will arrive through the
            // normal event path. Recovery scanner must not touch it.
            return false;
        }
        CloseReason closeReason = process.getCloseReason();
        String engineName = process.getThinkEngine();

        long durationMs = computeDurationMs(process);

        // Categorise the closure first.
        if (closeReason == null) {
            log.warn("Magrathea listener: ThinkProcess {} closed without closeReason — technical_error",
                    processId);
            publish(task, "technical_error", null, "process closed without closeReason",
                    durationMs, null);
            return true;
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
        return true;
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
