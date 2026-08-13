package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Agent-task executor (plan §4.1). Spawns a ThinkProcess via the
 * existing {@link RecipeResolver} + {@link ThinkProcessService} +
 * {@link ThinkEngineService} stack, links the spawned process id to
 * the calling {@code magrathea_tasks} row, and returns
 * {@link Optional#empty()} — completion arrives asynchronously through
 * {@link MagratheaThinkProcessCompletionListener}.
 *
 * <h3>YAML</h3>
 * <pre>
 * plan:
 *   type: agent_task
 *   recipe: jeltz                          # required — recipe in the cascade
 *   params:                                # → ThinkProcess.engineParams
 *     prompt: "Analyse the PR ..."
 *     schema: { ... }                      # Jeltz schema (engine-specific)
 *   storeAs: plan_output
 *   timeoutSeconds: 600
 *   on: { success: run_checks }
 *   catch: { agent_error: debug, timeout: escalate }
 * </pre>
 *
 * <p><b>{@code params.prompt} is delivered as an initial message.</b>
 * After {@code start()} the executor pushes it into the spawned
 * process's pending queue as {@code USER_CHAT_INPUT} — the same seeding
 * {@code SpawnActionExecutor} does for {@code initialMessage}. Reactive
 * engines (Ford/Vogon/Marvin/Arthur) need that: they wait for a message
 * and would otherwise idle forever, hanging the run with them. Jeltz
 * takes its prompt from {@code engineParams} and ignores the queue.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class AgentTaskExecutor implements MagratheaTypeExecutor {

    private static final String SPEC_RECIPE = "recipe";
    private static final String SPEC_PARAMS = "params";
    private static final String SPEC_PROMPT = "prompt";

    /** {@code fromUser} on the seeded initial message — a run, not a person. */
    private static final String MAGRATHEA_SENDER = "_magrathea";

    /** Removed from the agent's tools — see {@link #withoutDelegation}. */
    private static final Set<String> DELEGATION_TOOLS = Set.of("process_spawn");

    private final RecipeResolver recipeResolver;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final MagratheaSessionResolver sessionResolver;
    private final MagratheaTaskService taskService;
    private final de.mhus.vance.brain.scheduling.LaneScheduler laneScheduler;
    /** Lazy like the other consumers — the router pulls in the whole engine stack. */
    private final ObjectProvider<EngineMessageRouter> messageRouterProvider;

    @Override
    public MagratheaTaskType type() {
        return MagratheaTaskType.AGENT_TASK;
    }

    @Override
    public Optional<TaskOutcome> execute(MagratheaTaskContext context) {
        MagratheaStateSpec state = context.state();
        String recipeName = state.specString(SPEC_RECIPE);
        if (recipeName == null) {
            return Optional.of(TaskOutcome.failure(
                    "agent_task '" + state.name() + "' is missing required 'recipe:' field"));
        }
        Map<String, Object> callerParams = readParamsMap(state);

        AppliedRecipe applied;
        try {
            applied = recipeResolver.applyDefaulting(
                    context.tenantId(),
                    context.projectId(),
                    recipeName,
                    /* connectionProfile */ null,
                    callerParams);
        } catch (RuntimeException ex) {
            log.warn("Magrathea agent_task '{}' recipe resolve failed: {}",
                    state.name(), ex.getMessage());
            return Optional.of(TaskOutcome.failure(
                    "Recipe '" + recipeName + "' resolve failed: " + ex.getMessage()));
        }

        ThinkEngine engine = thinkEngineService.resolve(applied.engine())
                .orElse(null);
        if (engine == null) {
            return Optional.of(TaskOutcome.failure(
                    "Recipe '" + recipeName + "' references unknown engine '"
                            + applied.engine() + "'"));
        }

        SessionDocument session = sessionResolver.resolve(
                context.tenantId(), context.projectId(),
                context.workflowRunId(), context.startedBy());

        // Process name per task — keeps history scopable and lets a re-claim
        // see a stale process row even if the previous attempt vanished.
        String processName = state.name() + "_" + Instant.now().toEpochMilli();

        ThinkProcessDocument spawned;
        try {
            spawned = thinkProcessService.create(
                    context.tenantId(),
                    context.projectId(),
                    session.getSessionId(),
                    processName,
                    engine.name(),
                    engine.version(),
                    /*title*/ "Magrathea " + context.workflow().name() + "/" + state.name(),
                    /*goal*/ state.description(),
                    /*parentProcessId*/ null,
                    applied.params(),
                    applied.name(),
                    applied.promptOverride(),
                    applied.promptMode(),
                    withoutDelegation(applied, engine));
        } catch (RuntimeException ex) {
            log.warn("Magrathea agent_task '{}' ThinkProcess create failed: {}",
                    state.name(), ex.getMessage());
            return Optional.of(TaskOutcome.failure(
                    "ThinkProcess create failed: " + ex.getMessage()));
        }

        // Link sub-process id to the task BEFORE start so a fast-finishing
        // engine still finds a WAITING_SUBPROCESS row when the listener fires.
        taskService.linkSubProcess(context.taskId(), spawned.getId());

        // Start ON the process lane (like the other spawn sites), so start()
        // is serialized against any concurrent runTurn/steer for this process
        // — the Lane-Serialisierung invariant (CLAUDE.md). An off-lane start
        // right after linkSubProcess would race the completion listener path.
        Throwable startFailure = null;
        try {
            laneScheduler.submit(spawned.getId(), () -> {
                thinkEngineService.start(spawned);
                return null;
            }).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            startFailure = ie;
        } catch (java.util.concurrent.ExecutionException ee) {
            startFailure = ee.getCause() == null ? ee : ee.getCause();
        }
        if (startFailure != null) {
            log.warn("Magrathea agent_task '{}' engine.start failed: {}",
                    state.name(), startFailure.getMessage());
            // Unlink before closing so the completion listener won't match
            // the abandoned process to this task, then close it so it does
            // not linger as an unstarted orphan (holding a session slot).
            // The returned failure is the task's single authoritative
            // terminal outcome.
            taskService.unlinkSubProcess(context.taskId());
            try {
                thinkProcessService.closeProcess(
                        spawned.getId(), de.mhus.vance.api.thinkprocess.CloseReason.ABANDONED);
            } catch (RuntimeException closeEx) {
                log.warn("Magrathea agent_task '{}' could not close orphaned process '{}': {}",
                        state.name(), spawned.getId(), closeEx.toString());
            }
            return Optional.of(TaskOutcome.failure(
                    "Engine start failed: " + startFailure.getMessage()));
        }

        boolean steered = pushInitialMessage(applied, spawned.getId(), state.name());

        log.info("Magrathea agent_task '{}' spawned recipe='{}' engine='{}' subProcessId='{}' steered={}",
                state.name(), recipeName, applied.engine(), spawned.getId(), steered);

        // Async — listener fires the TaskCompletedEvent when the sub-process closes.
        return Optional.empty();
    }

    /**
     * The agent's tool surface minus the delegation tools.
     *
     * <p>A workflow state is a step in a plan the author wrote down. An
     * agent that spawns its own workers inside that step builds a second
     * plan next to it — invisible in the diagram, invisible in the run
     * view, and past the guard rail: {@code bounds.maxTaskSpawns} counts
     * workflow tasks, not processes an agent starts on its own.
     *
     * <p>It also keeps completion decidable. An agent that delegated ends
     * its turn at {@code IDLE} waiting for its worker, which is
     * indistinguishable from {@code IDLE} meaning done — and the turn-end
     * rule in {@code MagratheaThinkProcessCompletionListener} would cut
     * the step short. Without delegation, {@code IDLE} means done, full
     * stop.
     *
     * <p>Fan-out belongs in the workflow: more states, or a
     * {@code workflow_task} sub-run. Both show up in the plan and count
     * against its bounds.
     */
    private static Set<String> withoutDelegation(AppliedRecipe applied, ThinkEngine engine) {
        Set<String> effective = applied.effectiveAllowedTools() != null
                ? applied.effectiveAllowedTools()
                : engine.allowedTools();
        if (effective == null) return null;
        Set<String> reduced = new LinkedHashSet<>(effective);
        return reduced.removeAll(DELEGATION_TOOLS) ? Set.copyOf(reduced) : effective;
    }

    /**
     * Deliver {@code params.prompt} to the spawned process as an initial
     * {@code USER_CHAT_INPUT}, the same way {@code SpawnActionExecutor}
     * seeds {@code initialMessage}: push after {@code start()} so the
     * pending queue plus auto-wakeup drive the first turn.
     *
     * <p>Without this a reactive engine never runs. Ford, Vogon, Marvin and
     * Arthur all wait for a message — Ford's {@code start()} says so
     * outright ("workers spawned with steerContent immediately drain that
     * input"). It reaches them nowhere else: this was the only spawn site
     * in the system that created a process and never spoke to it, so an
     * {@code agent_task} naming a reactive recipe spawned a worker that sat
     * idle forever, and the run waited on it forever with it.
     *
     * <p>Pushed whenever a prompt is present, without asking which engine
     * wants one. Jeltz reads its prompt from {@code engineParams} and
     * closes on its first turn, so the extra pending entry costs nothing
     * there — and an engine list here would be one more thing to keep
     * current.
     */
    private boolean pushInitialMessage(AppliedRecipe applied, String processId, String stateName) {
        Object raw = applied.params() == null ? null : applied.params().get(SPEC_PROMPT);
        if (!(raw instanceof String prompt) || prompt.isBlank()) return false;

        EngineMessageRouter router = messageRouterProvider.getIfAvailable();
        if (router == null) {
            log.warn("Magrathea agent_task '{}' — EngineMessageRouter unavailable, "
                    + "initial prompt not delivered to process '{}'", stateName, processId);
            return false;
        }
        boolean delivered = router.dispatch(
                /* senderProcessId — a workflow run is not a process */ null,
                processId,
                PendingMessageDocument.builder()
                        .type(PendingMessageType.USER_CHAT_INPUT)
                        .at(Instant.now())
                        .fromUser(MAGRATHEA_SENDER)
                        .content(prompt)
                        .build());
        if (!delivered) {
            log.warn("Magrathea agent_task '{}' — initial prompt dispatch failed for process '{}'",
                    stateName, processId);
        }
        return delivered;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readParamsMap(MagratheaStateSpec state) {
        Object raw = state.specField(SPEC_PARAMS);
        if (raw == null) return Map.of();
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(
                "agent_task '" + state.name() + "' params must be a map");
    }
}
