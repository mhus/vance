package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>Jeltz pulls everything from {@code engineParams}, so the executor
 * does not send an initial steer. For reactive engines (Ford/Vogon/
 * Marvin/Arthur) that need an initial user message, set
 * {@code params.prompt} — the recipe template renders it; or a future
 * etappe adds an {@code initialMessage:} field at the state level.
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

    private final RecipeResolver recipeResolver;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final MagratheaSessionResolver sessionResolver;
    private final MagratheaTaskService taskService;

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
                    applied.effectiveAllowedTools());
        } catch (RuntimeException ex) {
            log.warn("Magrathea agent_task '{}' ThinkProcess create failed: {}",
                    state.name(), ex.getMessage());
            return Optional.of(TaskOutcome.failure(
                    "ThinkProcess create failed: " + ex.getMessage()));
        }

        // Link sub-process id to the task BEFORE start so a fast-finishing
        // engine still finds a WAITING_SUBPROCESS row when the listener fires.
        taskService.linkSubProcess(context.taskId(), spawned.getId());

        try {
            thinkEngineService.start(spawned);
        } catch (RuntimeException ex) {
            log.warn("Magrathea agent_task '{}' engine.start failed: {}",
                    state.name(), ex.getMessage());
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
                    "Engine start failed: " + ex.getMessage()));
        }

        log.info("Magrathea agent_task '{}' spawned recipe='{}' engine='{}' subProcessId='{}'",
                state.name(), recipeName, applied.engine(), spawned.getId());

        // Async — listener fires the TaskCompletedEvent when the sub-process closes.
        return Optional.empty();
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
