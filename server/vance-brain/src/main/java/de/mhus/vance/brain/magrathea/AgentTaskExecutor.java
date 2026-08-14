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
 * Directly after {@code start()}, on the same lane task so no turn can
 * slip in between, the executor pushes it into the spawned
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

    /**
     * How much of the owning process's history to put in front of the
     * worker's prompt — {@code none} / {@code summary} / {@code all} /
     * {@code strength:<min>} / {@code last:<n>}, as {@code InheritLevel}
     * parses them.
     */
    private static final String SPEC_INHERIT_CONTEXT = "inheritContext";
    private static final String INHERIT_NONE = "none";

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
    private final MagratheaTimeoutScheduler timeoutScheduler;
    /** Wraps a worker prompt with the owning process's context — see inheritContext. */
    private final de.mhus.vance.brain.inherit.ParentContextSpawnHelper parentContextSpawnHelper;

    @Override
    public MagratheaTaskType type() {
        return MagratheaTaskType.AGENT_TASK;
    }

    /**
     * {@code inheritContext:} needs an owning process, because inheriting
     * context means inheriting <em>somebody's</em>.
     *
     * <p>This is the one place a capability is genuinely a "cannot": a
     * headless run has no conversation behind it, so the instruction has
     * nothing to refer to. Silently ignoring it would be worse than
     * refusing — the plan would run, the worker would go in blind, and the
     * only symptom would be answers that keep missing context nobody can
     * see was dropped.
     */
    @Override
    public Set<de.mhus.vance.api.magrathea.RunCapability> requires(MagratheaStateSpec state) {
        String inherit = state.specString(SPEC_INHERIT_CONTEXT);
        if (inherit == null || inherit.isBlank() || INHERIT_NONE.equalsIgnoreCase(inherit.trim())) {
            return Set.of();
        }
        return Set.of(de.mhus.vance.api.magrathea.RunCapability.OWNER_PROCESS);
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

        // A bound run works inside the session it belongs to; only a run
        // that belongs to nobody gets the synthetic one. Same reason Marvin
        // spawns its workers into its own session: the step's history
        // belongs with the conversation that asked for it, and the context
        // helper resolves the parent against that session.
        String sessionId = context.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            SessionDocument session = sessionResolver.resolve(
                    context.tenantId(), context.projectId(),
                    context.workflowRunId(), context.startedBy());
            sessionId = session.getSessionId();
        }

        // Process name per task — keeps history scopable and lets a re-claim
        // see a stale process row even if the previous attempt vanished.
        String processName = state.name() + "_" + Instant.now().toEpochMilli();

        ThinkProcessDocument spawned;
        try {
            spawned = thinkProcessService.create(
                    context.tenantId(),
                    context.projectId(),
                    sessionId,
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
        //
        // The initial message goes into the SAME lane task, right behind
        // start(). Vogon and Marvin schedule their first turn from start()
        // itself, and that turn only runs once this task releases the lane —
        // so seeding here is what guarantees the prompt is in the pending
        // queue before the first turn reads it. Pushed from outside the lane
        // it raced that turn, and a turn that ends IDLE completes the task
        // (MagratheaThinkProcessCompletionListener#completeAfterTurn): the
        // step reported success and the prompt landed in a closed process.
        boolean[] steeredHolder = new boolean[1];
        Throwable startFailure = null;
        try {
            laneScheduler.submit(spawned.getId(), () -> {
                thinkEngineService.start(spawned);
                steeredHolder[0] = pushInitialMessage(
                        applied, spawned.getId(), state.name(),
                        state.specString(SPEC_INHERIT_CONTEXT), context.ownerProcessId());
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

        // Deadline before the wait: from here the task is asynchronous and
        // only the timer can end it if the agent never comes back.
        timeoutScheduler.arm(context, state);

        log.info("Magrathea agent_task '{}' spawned recipe='{}' engine='{}' subProcessId='{}' steered={}",
                state.name(), recipeName, applied.engine(), spawned.getId(), steeredHolder[0]);

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
     * <p><b>Called on the process lane, inside the same task as
     * {@code start()}.</b> Vogon and Marvin schedule their first turn from
     * {@code start()}, and that turn is a lane task queued behind this one —
     * seeding from outside the lane raced it, and since a turn that ends
     * {@code IDLE} completes the workflow task, the step could report
     * success before the prompt had been delivered at all.
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
    private boolean pushInitialMessage(
            AppliedRecipe applied,
            String processId,
            String stateName,
            @org.jspecify.annotations.Nullable String inheritContext,
            @org.jspecify.annotations.Nullable String ownerProcessId) {
        Object raw = applied.params() == null ? null : applied.params().get(SPEC_PROMPT);
        if (!(raw instanceof String prompt) || prompt.isBlank()) return false;

        // Give the worker the owning conversation to stand in, when the
        // state asked for it. Same helper every other spawn path uses, so
        // the block reads identically wherever a worker comes from — and
        // when there is nothing to inherit it appends the pointer to
        // process_history_text instead, leaving the worker a way back.
        String wrapped = parentContextSpawnHelper.wrap(inheritContext, ownerProcessId, prompt);
        if (wrapped != null && !wrapped.isBlank()) {
            prompt = wrapped;
        }

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
