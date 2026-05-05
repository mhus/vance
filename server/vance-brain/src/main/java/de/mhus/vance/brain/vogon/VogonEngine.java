package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.vogon.BranchAction;
import de.mhus.vance.api.vogon.CheckpointSpec;
import de.mhus.vance.api.vogon.CheckpointType;
import de.mhus.vance.api.vogon.GateSpec;
import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.ScorerSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import de.mhus.vance.api.vogon.StrategyState;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Vogon — the deterministic multi-phase strategy engine. Runs a
 * {@link StrategySpec} from start to finish: each phase spawns a
 * worker (synchronously, mirroring Marvin's pattern), optionally
 * pauses on a checkpoint inbox-item, then evaluates its gate
 * before advancing.
 *
 * <p>State is persistent on
 * {@code ThinkProcessDocument.engineParams.strategyState} — so a
 * Brain restart resumes the run on the next lane-turn. v1 supports
 * linear phase lists (no loops/forks/escalations); v2 brings
 * those primitives in. See {@code specification/vogon-engine.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VogonEngine implements ThinkEngine {

    public static final String NAME = "vogon";
    public static final String VERSION = "0.1.0";

    /** Set on {@code engineParams[STATE_KEY]} as the persisted
     *  {@link StrategyState} for this process. */
    public static final String STATE_KEY = "strategyState";

    /** {@code engineParams[STRATEGY_KEY]} — strategy name to run.
     *  Resolved once via {@link StrategyResolver} at {@link #start};
     *  the resolved plan is then frozen under {@link #PLAN_KEY}. */
    public static final String STRATEGY_KEY = "strategy";

    /**
     * {@code engineParams[PLAN_KEY]} — frozen YAML snapshot of the
     * strategy plan. Populated at {@link #start} from either the
     * resolved plan ({@link #STRATEGY_KEY} present) or an inline
     * YAML string supplied by the caller (so callers can spawn a
     * one-off strategy without first persisting a document).
     *
     * <p>Format: a YAML string identical to what
     * {@code StrategyResolver.parseStrategy} accepts. Storing as
     * text keeps the snapshot human-readable in MongoDB and lets the
     * existing parser handle the polymorphic {@code BranchAction}
     * sealed hierarchy without bespoke Jackson plumbing.
     */
    public static final String PLAN_KEY = "strategyPlanYaml";

    /**
     * Separator used for synthetic flag keys ({@code <phase>_completed}
     * etc.). MongoDB rejects '.' in map keys (path-separator), so
     * we standardise on '_' across the engine, the bundled
     * strategies.yaml, and the spec. User-defined {@code storeAs}
     * keys are stored verbatim — the engine doesn't munge them, but
     * authors should also avoid dots there.
     */
    private static final String FLAG_SEP = "_";

    private static String phaseFlag(String phaseName, String suffix) {
        return phaseName + FLAG_SEP + suffix;
    }

    /**
     * Top of {@link StrategyState#getCurrentPhasePath()} — the phase
     * Vogon is currently working on. {@code null} when the strategy
     * has finished and no phase is active anymore.
     *
     * <p>For linear strategies the path is always one element deep,
     * so this is equivalent to "the current phase". Loop sub-phases
     * (Phase B+) carry deeper paths.
     */
    static @Nullable String currentLeafPhaseName(StrategyState state) {
        java.util.List<String> path = state.getCurrentPhasePath();
        return path == null || path.isEmpty() ? null : path.get(path.size() - 1);
    }

    /**
     * Replace the leaf segment of the path with {@code newName} —
     * used for "advance to the next phase on the same level".
     */
    static void replaceLeafPhase(StrategyState state, String newName) {
        java.util.List<String> path = state.getCurrentPhasePath();
        if (path.isEmpty()) {
            path.add(newName);
        } else {
            path.set(path.size() - 1, newName);
        }
    }

    /**
     * Qualified storage key for a phase. Top-level phases use their
     * bare name; sub-phases inside a loop use {@code <loopName>/<subName>}
     * so a sub-phase can't collide with an unrelated top-level phase
     * (and so loop-body invalidation can drop everything by prefix).
     */
    static String qualifiedPhaseKey(StrategyState state, PhaseSpec phase) {
        java.util.List<String> path = state.getCurrentPhasePath();
        if (path.size() > 1 && phase.getName().equals(path.get(path.size() - 1))) {
            return path.get(0) + PhaseAdvancer.QUALIFIED_KEY_SEP + phase.getName();
        }
        return phase.getName();
    }

    private static final String SETTINGS_REF_TYPE = "tenant";

    private final StrategyResolver strategyResolver;
    private final DocumentService documentService;
    private final de.mhus.vance.shared.session.SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final InboxItemService inboxItemService;
    private final RecipeResolver recipeResolver;
    private final ProcessEventEmitter eventEmitter;
    private final LaneScheduler laneScheduler;
    private final ObjectMapper objectMapper;
    private final de.mhus.vance.brain.progress.ProgressEmitter progressEmitter;
    /** Lazy — Vogon spawns workers via the engine service which
     *  bootstraps every engine bean, including this one. */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Vogon (Strategy Runner)";
    }

    @Override
    public String description() {
        return "Deterministic multi-phase strategy runner. Loads a YAML "
                + "strategy plan, spawns one worker per phase, blocks on "
                + "user checkpoints via the inbox, and advances when "
                + "phase gates are satisfied.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        // Vogon decides deterministically — no LLM tool calls of its
        // own. Worker recipes carry their own tool pools.
        return Set.of();
    }

    @Override
    public boolean asyncSteer() {
        // Like Marvin: Vogon's lane is event-driven (worker DONE,
        // inbox answer); orchestrators shouldn't block waiting for
        // a per-steer reply.
        return true;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        StrategySpec strategy = resolveAndFreezePlan(process);
        if (strategy.getPhases().isEmpty()) {
            throw new IllegalStateException(
                    "Strategy '" + strategy.getName() + "' has no phases");
        }
        StrategyState state = StrategyState.builder()
                .strategy(strategy.getName())
                .strategyVersion(strategy.getVersion())
                .build();
        state.getCurrentPhasePath().add(strategy.getPhases().get(0).getName());
        persistState(process, state);
        log.info("Vogon.start tenant='{}' session='{}' id='{}' strategy='{}' phase='{}'",
                process.getTenantId(), process.getSessionId(), process.getId(),
                strategy.getName(), currentLeafPhaseName(state));
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        // Trigger first turn on Vogon's own lane.
        eventEmitter.scheduleTurn(process.getId());
    }

    /**
     * Resolve the strategy for {@code process} and freeze it as a
     * YAML snapshot under {@link #PLAN_KEY}. Two valid input modes:
     *
     * <ul>
     *   <li><b>By name</b> — {@code engineParams.strategy = "<name>"}
     *       (cascade lookup via {@link StrategyResolver}).</li>
     *   <li><b>Inline</b> — {@code engineParams.strategyPlanYaml =
     *       "<yaml-string>"} (parsed directly, no document needed).</li>
     * </ul>
     *
     * <p>Setting both is rejected. Snapshot semantics: subsequent
     * runTurn-calls read exclusively from the frozen YAML string,
     * even if the underlying document is edited mid-run.
     */
    private StrategySpec resolveAndFreezePlan(ThinkProcessDocument process) {
        String strategyName = stringParam(process, STRATEGY_KEY, null);
        Object inlineRaw = process.getEngineParams() == null
                ? null : process.getEngineParams().get(PLAN_KEY);
        if (strategyName != null && inlineRaw != null) {
            throw new IllegalArgumentException(
                    "Vogon.start: engineParams.strategy and engineParams.strategyPlanYaml "
                            + "are mutually exclusive — id='" + process.getId() + "'");
        }
        if (strategyName == null && inlineRaw == null) {
            throw new IllegalStateException(
                    "Vogon.start requires engineParams.strategy or engineParams.strategyPlanYaml — id='"
                            + process.getId() + "'");
        }

        StrategySpec strategy;
        String planYaml;
        if (inlineRaw != null) {
            if (!(inlineRaw instanceof String inlineYaml) || inlineYaml.isBlank()) {
                throw new IllegalArgumentException(
                        "Vogon.start: engineParams.strategyPlanYaml must be a non-blank "
                                + "YAML string — id='" + process.getId() + "'");
            }
            strategy = StrategyResolver.parseStrategy(inlineYaml, "inline-plan");
            planYaml = inlineYaml;
        } else {
            strategy = strategyResolver.find(
                    strategyName, process.getTenantId(), resolveProjectId(process))
                    .orElseThrow(() -> new IllegalStateException(
                            "Unknown strategy '" + strategyName + "'"));
            planYaml = readStrategyDocument(strategyName,
                    process.getTenantId(), resolveProjectId(process));
        }
        // Freeze the YAML snapshot on engineParams so runTurn never
        // re-reads the document.
        Map<String, Object> params = process.getEngineParams() == null
                ? new LinkedHashMap<>() : process.getEngineParams();
        params.put(PLAN_KEY, planYaml);
        process.setEngineParams(params);
        thinkProcessService.replaceEngineParams(process.getId(), params);
        return strategy;
    }

    /**
     * Re-parse the frozen YAML snapshot from
     * {@code engineParams.strategyPlanYaml}. Lazy-migration fallback:
     * if the snapshot field is missing (legacy process started before
     * the snapshot-fix landed) and {@link StrategyState#getStrategy}
     * carries a name, resolve from the document cascade once and
     * persist the snapshot — the next turn will hit the fast path.
     */
    private StrategySpec loadStrategySnapshot(
            ThinkProcessDocument process, StrategyState state) {
        Map<String, Object> params = process.getEngineParams();
        Object snapshotRaw = params == null ? null : params.get(PLAN_KEY);
        if (snapshotRaw instanceof String yaml && !yaml.isBlank()) {
            return StrategyResolver.parseStrategy(yaml, "snapshot");
        }
        // Legacy / lazy migration path.
        String name = state.getStrategy();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(
                    "Vogon process has neither strategyPlanYaml snapshot nor "
                            + "strategyState.strategy — id='" + process.getId() + "'");
        }
        StrategySpec resolved = strategyResolver.find(
                        name, process.getTenantId(), resolveProjectId(process))
                .orElseThrow(() -> new IllegalStateException(
                        "Strategy '" + name + "' missing at runtime "
                                + "(no snapshot, document gone)"));
        String yaml = readStrategyDocument(name,
                process.getTenantId(), resolveProjectId(process));
        Map<String, Object> updated = params == null ? new LinkedHashMap<>() : params;
        updated.put(PLAN_KEY, yaml);
        process.setEngineParams(updated);
        thinkProcessService.replaceEngineParams(process.getId(), updated);
        log.info("Vogon id='{}' strategy snapshot lazy-migrated from name='{}'",
                process.getId(), name);
        return resolved;
    }

    /**
     * Read the YAML text of {@code name}'s strategy document — the
     * same document {@link StrategyResolver} just resolved against,
     * so the lookup must succeed. We capture the on-disk YAML
     * verbatim instead of bean-dumping the parsed {@link StrategySpec}
     * (the {@code BranchAction} sealed hierarchy resists naive
     * bean-conversion, and the on-disk text is already the
     * authoritative form).
     */
    private String readStrategyDocument(
            String name, String tenantId, @Nullable String projectId) {
        String path = StrategyResolver.STRATEGIES_PREFIX
                + name.toLowerCase().trim() + ".yaml";
        return documentService.lookupCascade(tenantId, projectId, path)
                .map(de.mhus.vance.shared.document.LookupResult::content)
                .orElseThrow(() -> new IllegalStateException(
                        "Strategy document '" + path
                                + "' vanished between resolve and snapshot"));
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Vogon.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Vogon.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    // ──────────────────── runTurn ────────────────────

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        StrategyState initialState = loadState(process);
        StrategySpec strategy = loadStrategySnapshot(process, initialState);
        StrategyState state = initialState;
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            runTurnInner(process, ctx, strategy, state);
        } finally {
            // Snapshot the (possibly mutated) phase state to the
            // user-progress side-channel — runs on every turn end,
            // including the failure path.
            try {
                emitPlanSnapshot(process, strategy, loadState(process));
            } catch (RuntimeException pe) {
                log.debug("Vogon id='{}' plan-snapshot push failed: {}",
                        process.getId(), pe.toString());
            }
        }
    }

    private void runTurnInner(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            StrategySpec strategy,
            StrategyState initialState) {
        StrategyState state = initialState;
        try {
            // 1. Fold pending events (inbox answers, parent steers).
            for (SteerMessage msg : ctx.drainPending()) {
                consumePending(process, state, msg);
            }
            // Re-load the fresh persisted state — consumePending
            // saves on its own.
            state = loadState(process);

            // 2. Strategy already DONE? Finalize.
            if (state.isStrategyComplete()) {
                log.info("Vogon id='{}' strategy '{}' complete — DONE",
                        process.getId(), state.getStrategy());
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
                return;
            }

            // 3. Pick the current phase. Null = no current → done.
            //    `resolveActivePhase` drills into a loop's first
            //    sub-phase on first encounter (and bumps its counter
            //    to 1), so the rest of runTurn always sees a runnable
            //    leaf phase, never the loop wrapper.
            PhaseSpec phase = PhaseAdvancer.resolveActivePhase(strategy, state);
            if (phase == null) {
                state.setStrategyComplete(true);
                persistState(process, state);
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
                return;
            }
            // resolveActivePhase may have pushed onto the path; persist
            // the bookkeeping so a Brain restart resumes inside the
            // loop body rather than re-entering it.
            persistState(process, state);

            // 4. Already blocked on a checkpoint? Yield, wait for the
            //    answer to arrive via consumePending.
            if (state.getPendingCheckpoint() != null) {
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
                return;
            }

            // 5. Evaluate the phase: spawn worker if not yet done; then
            //    create checkpoint if defined; then check gate; advance.
            advancePhase(process, ctx, strategy, state, phase);
        } catch (RuntimeException e) {
            log.warn("Vogon runTurn failed id='{}': {}", process.getId(), e.toString(), e);
            thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            throw e;
        }
    }

    // ──────────────────── Phase execution ────────────────────

    private void advancePhase(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            StrategySpec strategy,
            StrategyState state,
            PhaseSpec phase) {
        Map<String, Object> params = effectiveParams(process, strategy);
        VogonSubstitutor sub = new VogonSubstitutor(params, state);
        String phaseName = phase.getName();
        boolean workerDone = isFlagTrue(state, phaseFlag(phaseName, "completed"));

        // 1. Worker step (if defined and not yet done).
        if (phase.getWorker() != null && !workerDone) {
            BranchActionExecutor.Result override = spawnAndAwaitWorker(
                    process, ctx, strategy, phase, params, state, sub);
            // Re-load after spawn — flag was set inside.
            state = loadState(process);
            workerDone = isFlagTrue(state, phaseFlag(phaseName, "completed"));
            if (!workerDone) {
                // Worker FAILED — flag failed=true is set; mark process STALE.
                if (isFlagTrue(state, phaseFlag(phaseName, "failed"))) {
                    log.warn("Vogon id='{}' phase '{}' worker FAILED — process stale",
                            process.getId(), phaseName);
                    thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
                    return;
                }
                // Defensive: shouldn't happen with synchronous spawn,
                // but yield rather than loop.
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
                return;
            }
            // If the scorer fired a non-CONTINUE branch, take that path
            // instead of running the normal checkpoint/gate/advance flow.
            if (override != null
                    && override.kind() != BranchActionExecutor.ResultKind.CONTINUE) {
                applyBranchOverride(process, strategy, state, phase, override);
                return;
            }
        }

        // 2. Checkpoint step (if defined and not yet answered).
        if (phase.getCheckpoint() != null
                && !checkpointAnswered(state, phase.getCheckpoint())) {
            createCheckpoint(process, ctx, phase, state, sub);
            // After creating, BLOCKED — wait for the answer.
            return;
        }

        // 3. Gate evaluation.
        if (!gateSatisfied(phase.getGate(), state)) {
            // Gate not met — yield. Either an external event will
            // set the missing flag, or the strategy is stuck — that's
            // a config bug, not Vogon's job to recover.
            log.info("Vogon id='{}' phase '{}' gate not yet satisfied — waiting",
                    process.getId(), phaseName);
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
            return;
        }

        // 4. Advance to next phase.
        advanceToNext(process, strategy, state, phase);
        // Schedule the next turn so the new phase is picked up.
        eventEmitter.scheduleTurn(process.getId());
    }

    /**
     * Synchronous worker spawn — same pattern as
     * {@code MarvinEngine.runWorker}: spawn → submit-and-wait →
     * read reply → record artifact + flag → stop worker.
     */
    private BranchActionExecutor.@Nullable Result spawnAndAwaitWorker(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            StrategySpec strategy,
            PhaseSpec phase,
            Map<String, Object> params,
            StrategyState state,
            VogonSubstitutor sub) {
        String recipeName = sub.apply(phase.getWorker());
        if (recipeName == null || recipeName.isBlank()) {
            log.warn("Vogon id='{}' phase '{}' has worker template that resolved empty — failing phase",
                    process.getId(), phase.getName());
            state.getFlags().put(phaseFlag(phase.getName(), "failed"), true);
            persistState(process, state);
            return null;
        }
        String steerContent = sub.apply(phase.getWorkerInput());
        ThinkProcessDocument child;
        try {
            AppliedRecipe applied = recipeResolver.apply(
                    process.getTenantId(), ctx.projectId(), recipeName,
                    process.getConnectionProfile(), /*params*/ null);
            ThinkEngine targetEngine = thinkEngineServiceProvider.getObject()
                    .resolve(applied.engine())
                    .orElseThrow(() -> new IllegalStateException(
                            "Recipe '" + applied.name() + "' references unknown engine '"
                                    + applied.engine() + "'"));
            // Include an attempt-counter so a retry after STALE
            // doesn't collide with the previous worker's name. The
            // counter is per-phase and lives in the state.
            int attempt = state.getLoopCounters()
                    .merge("phase_attempt_" + phase.getName(), 1, Integer::sum);
            String childName = "vogon-" + process.getId()
                    + "-" + phase.getName() + "-" + attempt;
            child = thinkProcessService.create(
                    process.getTenantId(),
                    process.getProjectId(),
                    process.getSessionId(),
                    childName,
                    targetEngine.name(),
                    targetEngine.version(),
                    "Vogon worker for phase " + phase.getName(),
                    steerContent.isBlank() ? phase.getName() : steerContent,
                    process.getId(),
                    applied.params(),
                    applied.name(),
                    applied.promptOverride(),
                    applied.promptOverrideSmall(),
                    applied.promptMode(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools(),
                    applied.connectionProfile(),
                    applied.defaultActiveSkills(),
                    applied.allowedSkills() == null
                            ? null : java.util.Set.copyOf(applied.allowedSkills()));
            state.getWorkerProcessIds().put(qualifiedPhaseKey(state, phase), child.getId());
            persistState(process, state);
            thinkEngineServiceProvider.getObject().start(child);
            log.info("Vogon id='{}' phase '{}' spawned worker child='{}' recipe='{}'",
                    process.getId(), phase.getName(), child.getId(), applied.name());
        } catch (RuntimeException e) {
            log.warn("Vogon id='{}' phase '{}' spawn failed: {}",
                    process.getId(), phase.getName(), e.toString());
            state.getFlags().put(phaseFlag(phase.getName(), "failed"), true);
            persistState(process, state);
            return null;
        }

        // Synchronous turn drive.
        BranchActionExecutor.Result branchResult = null;
        try {
            driveWorkerTurn(child, process.getId(), steerContent);
            String reply = readLastAssistantText(process.getTenantId(),
                    process.getSessionId(), child.getId());
            String phaseKey = qualifiedPhaseKey(state, phase);
            Map<String, Object> artifact = new LinkedHashMap<>();
            if (reply != null) artifact.put("result", reply);
            state.getPhaseArtifacts().put(phaseKey, artifact);
            state.getFlags().put(phaseFlag(phase.getName(), "completed"), true);
            persistState(process, state);
            log.info("Vogon id='{}' phase '{}' worker DONE — {} chars captured",
                    process.getId(), phase.getName(),
                    reply == null ? 0 : reply.length());

            // After-DONE hook: scorer (§2.5) or decider (§2.6) extracts
            // a structured verdict from the reply and may force a branch
            // (exitLoop / exitStrategy / jumpToPhase / escalateTo).
            // Scorer / decider are mutually exclusive — strategy-load
            // validation already enforces that. Phase J adds a third
            // option: outputSchema + postActions for executive workers
            // (worker delivers structured JSON, engine persists
            // deterministically via post-actions).
            if (phase.getScorer() != null) {
                branchResult = evaluateScorerWithCorrections(
                        process, child, strategy, state, phase, phaseKey, reply);
            } else if (phase.getDecider() != null) {
                branchResult = evaluateDeciderWithCorrections(
                        process, child, strategy, state, phase, phaseKey, reply);
            } else if (phase.getOutputSchema() != null
                    || phase.getPostActions() != null) {
                evaluateOutputSchemaAndPostActions(
                        process, child, strategy, state, phase, phaseKey, reply);
            }
        } catch (RuntimeException e) {
            log.warn("Vogon id='{}' phase '{}' worker turn failed: {}",
                    process.getId(), phase.getName(), e.toString());
            state.getFlags().put(phaseFlag(phase.getName(), "failed"), true);
            persistState(process, state);
        } finally {
            try {
                thinkEngineServiceProvider.getObject().stop(child);
            } catch (RuntimeException e) {
                log.warn("Vogon id='{}' worker stop failed for child='{}': {}",
                        process.getId(), child.getId(), e.toString());
            }
        }
        return branchResult;
    }

    /**
     * Run {@code phase.scorer}'s evaluator on {@code reply}; on schema
     * failure issue up-to {@code maxCorrections} re-prompts to the
     * worker before flagging the phase failed. Returns the executed
     * branch's {@link BranchActionExecutor.Result} (null when the
     * scorer never reached COMPLETED).
     */
    private BranchActionExecutor.@Nullable Result evaluateScorerWithCorrections(
            ThinkProcessDocument process,
            ThinkProcessDocument child,
            StrategySpec strategy,
            StrategyState state,
            PhaseSpec phase,
            String phaseKey,
            @Nullable String initialReply) {
        ScorerSpec scorer = phase.getScorer();
        if (scorer == null) return null;
        int max = scorer.getMaxCorrections() == null ? 2 : scorer.getMaxCorrections();
        String reply = initialReply;
        int attempts = 0;
        while (true) {
            ScorerEvaluator.Result r = ScorerEvaluator.evaluate(
                    strategy, state, phase, phaseKey, scorer, reply, objectMapper);
            if (r.outcome() == ScorerEvaluator.Outcome.COMPLETED) {
                persistState(process, state);
                log.info("Vogon id='{}' phase '{}' scorer COMPLETED → {}",
                        process.getId(), phase.getName(),
                        r.branchResult() == null ? "no-op" : r.branchResult().kind());
                return r.branchResult();
            }
            if (attempts >= max) {
                log.warn("Vogon id='{}' phase '{}' scorer schema invalid after {} "
                                + "correction attempts — failing phase. Last hint: {}",
                        process.getId(), phase.getName(), attempts, r.correctionHint());
                state.getFlags().put(phaseFlag(phase.getName(), "failed"), true);
                persistState(process, state);
                return null;
            }
            attempts++;
            log.info("Vogon id='{}' phase '{}' scorer correction {}/{} — re-prompt",
                    process.getId(), phase.getName(), attempts, max);
            try {
                driveWorkerTurn(child, process.getId(),
                        "Your last reply did not match the scorer schema: "
                                + r.correctionHint()
                                + "\nPlease re-emit the final JSON object correctly.");
                reply = readLastAssistantText(process.getTenantId(),
                        process.getSessionId(), child.getId());
            } catch (RuntimeException e) {
                log.warn("Vogon id='{}' phase '{}' correction re-prompt failed: {}",
                        process.getId(), phase.getName(), e.toString());
                state.getFlags().put(phaseFlag(phase.getName(), "failed"), true);
                persistState(process, state);
                return null;
            }
        }
    }

    private void driveWorkerTurn(
            ThinkProcessDocument child, String vogonProcessId, String content) {
        SteerMessage.UserChatInput message = new SteerMessage.UserChatInput(
                java.time.Instant.now(),
                /*idempotencyKey*/ null,
                "vogon:" + vogonProcessId,
                content == null ? "" : content);
        try {
            laneScheduler.submit(child.getId(),
                    () -> thinkEngineServiceProvider.getObject().steer(child, message)).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Vogon worker interrupted child='" + child.getId() + "'", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new RuntimeException(
                    "Vogon worker turn failed child='" + child.getId()
                            + "': " + cause.getMessage(), cause);
        }
    }

    private @Nullable String readLastAssistantText(
            String tenantId, String sessionId, String workerProcessId) {
        List<ChatMessageDocument> history = chatMessageService.history(
                tenantId, sessionId, workerProcessId);
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessageDocument m = history.get(i);
            if (m.getRole() == ChatRole.ASSISTANT && m.getContent() != null
                    && !m.getContent().isBlank()) {
                return m.getContent();
            }
        }
        return null;
    }

    // ──────────────────── Checkpoints ────────────────────

    private boolean checkpointAnswered(StrategyState state, CheckpointSpec spec) {
        // The flag-key is the storeAs (if set); otherwise the
        // generic <phase>_checkpointAnswered marker. Either way,
        // presence of the key means we've routed an answer already.
        if (spec.getStoreAs() != null) {
            return state.getFlags().containsKey(spec.getStoreAs());
        }
        return state.getFlags().containsKey(
                phaseFlag(currentLeafPhaseName(state), "checkpointAnswered"));
    }

    private void createCheckpoint(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            PhaseSpec phase,
            StrategyState state,
            VogonSubstitutor sub) {
        CheckpointSpec spec = phase.getCheckpoint();
        if (spec == null) return;
        InboxItemType itemType = mapCheckpointType(spec.getType());
        Criticality crit = parseCriticality(spec.getCriticality());
        String message = sub.apply(spec.getMessage());
        Map<String, Object> payload = new LinkedHashMap<>(spec.getPayload());
        if (spec.getType() == CheckpointType.DECISION
                && !spec.getOptions().isEmpty()) {
            payload.put("options", spec.getOptions());
        }
        if (crit == Criticality.LOW && spec.getDefaultValue() != null) {
            payload.put("default", spec.getDefaultValue());
        }
        String assignee = ctx.userId() == null ? "system" : ctx.userId();
        InboxItemDocument toCreate = InboxItemDocument.builder()
                .tenantId(process.getTenantId())
                .originatorUserId("vogon:" + process.getId())
                .assignedToUserId(assignee)
                .originProcessId(process.getId())
                .originSessionId(process.getSessionId())
                .type(itemType)
                .criticality(crit)
                .tags(spec.getTags())
                .title(message.isBlank()
                        ? "Checkpoint: " + phase.getName() : message)
                .body(null)
                .payload(payload)
                .requiresAction(true)
                .build();
        InboxItemDocument saved = inboxItemService.create(toCreate);
        StrategyState.PendingCheckpoint pending = StrategyState.PendingCheckpoint.builder()
                .phaseName(phase.getName())
                .inboxItemId(saved.getId())
                .type(spec.getType())
                .storeAs(spec.getStoreAs())
                .build();
        state.setPendingCheckpoint(pending);
        persistState(process, state);
        log.info("Vogon id='{}' phase '{}' checkpoint inbox='{}' type={} crit={}",
                process.getId(), phase.getName(), saved.getId(),
                spec.getType(), crit);
    }

    private static InboxItemType mapCheckpointType(CheckpointType type) {
        return switch (type) {
            case APPROVAL -> InboxItemType.APPROVAL;
            case DECISION -> InboxItemType.DECISION;
            case FEEDBACK -> InboxItemType.FEEDBACK;
        };
    }

    private static Criticality parseCriticality(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return Criticality.NORMAL;
        try {
            return Criticality.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Criticality.NORMAL;
        }
    }

    // ──────────────────── Pending → Vogon ────────────────────

    private void consumePending(
            ThinkProcessDocument process, StrategyState state, SteerMessage msg) {
        switch (msg) {
            case SteerMessage.ProcessEvent pe -> handleWorkerEvent(process, state, pe);
            case SteerMessage.InboxAnswer ia -> handleInboxAnswer(process, state, ia);
            case SteerMessage.UserChatInput uci ->
                    log.debug("Vogon id='{}' ignoring chat input from='{}' (use checkpoints)",
                            process.getId(), uci.fromUser());
            case SteerMessage.ToolResult tr ->
                    log.debug("Vogon id='{}' ignoring async tool result tool='{}'",
                            process.getId(), tr.toolName());
            case SteerMessage.ExternalCommand ec ->
                    log.info("Vogon id='{}' external command '{}' — not yet routed",
                            process.getId(), ec.command());
            case SteerMessage.PeerEvent pe ->
                    log.debug("Vogon id='{}' ignoring peer event type='{}' (hub-only)",
                            process.getId(), pe.type());
        }
    }

    /**
     * Synchronous worker pattern means most worker DONEs are
     * already routed inline — but late-arriving FAILED/STOPPED
     * (e.g. orphaned worker that the spawner stopped after its
     * route) can still surface here. Mark the corresponding phase
     * .failed only if the phase isn't already advanced.
     */
    private void handleWorkerEvent(
            ThinkProcessDocument process, StrategyState state, SteerMessage.ProcessEvent event) {
        // Find which phase this worker belongs to.
        String phaseName = null;
        for (Map.Entry<String, String> e : state.getWorkerProcessIds().entrySet()) {
            if (event.sourceProcessId().equals(e.getValue())) {
                phaseName = e.getKey();
                break;
            }
        }
        if (phaseName == null) {
            return;
        }
        if (event.type() == ProcessEventType.FAILED
                && !isFlagTrue(state, phaseFlag(phaseName, "completed"))) {
            state.getFlags().put(phaseFlag(phaseName, "failed"), true);
            persistState(process, state);
            log.info("Vogon id='{}' phase '{}' worker FAILED via late event",
                    process.getId(), phaseName);
        }
    }

    private void handleInboxAnswer(
            ThinkProcessDocument process, StrategyState state, SteerMessage.InboxAnswer answer) {
        StrategyState.PendingCheckpoint pending = state.getPendingCheckpoint();
        if (pending == null) {
            log.debug("Vogon id='{}' got InboxAnswer but no pending checkpoint",
                    process.getId());
            return;
        }
        if (!answer.inboxItemId().equals(pending.getInboxItemId())) {
            log.debug("Vogon id='{}' InboxAnswer for unrelated item='{}' (waiting on '{}')",
                    process.getId(), answer.inboxItemId(), pending.getInboxItemId());
            return;
        }
        AnswerPayload payload = answer.answer();
        switch (payload.getOutcome()) {
            case DECIDED -> {
                Object value = extractAnswerValue(pending.getType(), payload);
                if (pending.getStoreAs() != null) {
                    state.getFlags().put(pending.getStoreAs(), value);
                }
                state.getFlags().put(
                        phaseFlag(pending.getPhaseName(), "checkpointAnswered"), true);
                state.setPendingCheckpoint(null);
                persistState(process, state);
                log.info("Vogon id='{}' phase '{}' checkpoint DECIDED storeAs='{}' value='{}'",
                        process.getId(), pending.getPhaseName(),
                        pending.getStoreAs(), value);
            }
            case INSUFFICIENT_INFO, UNDECIDABLE -> {
                state.setPendingCheckpoint(null);
                state.getFlags().put(
                        phaseFlag(pending.getPhaseName(), "checkpointAnswered"), true);
                state.getFlags().put(
                        phaseFlag(pending.getPhaseName(), "failed"), true);
                persistState(process, state);
                log.info("Vogon id='{}' phase '{}' checkpoint {} reason='{}' — phase failed",
                        process.getId(), pending.getPhaseName(),
                        payload.getOutcome(), payload.getReason());
            }
        }
    }

    private static @Nullable Object extractAnswerValue(
            CheckpointType type, AnswerPayload payload) {
        if (payload.getValue() == null) return null;
        return switch (type) {
            case APPROVAL -> payload.getValue().get("approved");
            case DECISION -> payload.getValue().get("chosen");
            case FEEDBACK -> payload.getValue().get("text");
        };
    }

    // ──────────────────── Gate / advance ────────────────────

    private boolean gateSatisfied(@Nullable GateSpec gate, StrategyState state) {
        if (gate == null) return true;
        if (!gate.getRequires().isEmpty()) {
            for (String flag : gate.getRequires()) {
                if (!isFlagTrue(state, flag)) return false;
            }
        }
        if (!gate.getRequiresAny().isEmpty()) {
            boolean any = false;
            for (String flag : gate.getRequiresAny()) {
                if (isFlagTrue(state, flag)) { any = true; break; }
            }
            if (!any) return false;
        }
        return true;
    }

    /**
     * Phase J — extract the worker's structured JSON output, validate
     * it against {@code phase.outputSchema} (re-prompting up to
     * {@code maxOutputCorrections} times), then run
     * {@code phase.postActions} with {@code ${output.X}} /
     * {@code ${flags.X}} / {@code ${params.X}} substitutions.
     *
     * <p>Designed for executive worker patterns: the worker only
     * delivers content, the engine deterministically persists it.
     */
    private void evaluateOutputSchemaAndPostActions(
            ThinkProcessDocument process,
            ThinkProcessDocument child,
            StrategySpec strategy,
            StrategyState state,
            PhaseSpec phase,
            String phaseKey,
            @Nullable String initialReply) {
        Map<String, Object> schema = phase.getOutputSchema();
        int maxCorrections = phase.getMaxOutputCorrections() == null
                ? 2 : phase.getMaxOutputCorrections();
        Map<String, Object> output = null;
        String reply = initialReply;
        int attempts = 0;
        // Only enter the validate-loop when a schema is set; otherwise
        // we have actions but no contract — still attempt a parse so
        // ${output.X} substitutions work, but don't fail the phase
        // if parsing fails.
        if (schema != null) {
            while (true) {
                de.mhus.vance.shared.util.JsonSchemaLight.Result vr;
                Map<String, Object> parsed = parseJsonReply(reply);
                if (parsed == null) {
                    vr = de.mhus.vance.shared.util.JsonSchemaLight.Result.fail(
                            java.util.List.of(
                                    "no JSON object found at the end of the reply"));
                } else {
                    vr = de.mhus.vance.shared.util.JsonSchemaLight.validate(parsed, schema);
                }
                if (vr.valid()) {
                    output = parsed;
                    break;
                }
                if (attempts >= maxCorrections) {
                    log.warn("Vogon id='{}' phase '{}' outputSchema invalid after {} "
                                    + "correction attempts: {}",
                            process.getId(), phase.getName(), attempts, vr.errorsJoined());
                    state.getFlags().put(phaseFlag(phase.getName(), "failed"), true);
                    persistState(process, state);
                    return;
                }
                attempts++;
                log.info("Vogon id='{}' phase '{}' output correction {}/{}: {}",
                        process.getId(), phase.getName(),
                        attempts, maxCorrections, vr.errorsJoined());
                try {
                    driveWorkerTurn(child, process.getId(),
                            "Your last reply did not match the required schema: "
                                    + vr.errorsJoined()
                                    + "\nRe-emit the final JSON object correctly.");
                    reply = readLastAssistantText(process.getTenantId(),
                            process.getSessionId(), child.getId());
                } catch (RuntimeException e) {
                    log.warn("Vogon id='{}' phase '{}' correction reprompt failed: {}",
                            process.getId(), phase.getName(), e.toString());
                    state.getFlags().put(phaseFlag(phase.getName(), "failed"), true);
                    persistState(process, state);
                    return;
                }
            }
        } else {
            output = parseJsonReply(reply);
        }

        // Persist the parsed output as part of the phase artefact for
        // audit + cross-phase ${phases.X.output.Y} substitution.
        Map<String, Object> artifact = state.getPhaseArtifacts().get(phaseKey);
        if (artifact == null) {
            artifact = new LinkedHashMap<>();
            state.getPhaseArtifacts().put(phaseKey, artifact);
        }
        if (output != null) {
            artifact.put("output", output);
        }
        persistState(process, state);

        // Run postActions with full substitution context (output +
        // params + flags + phases).
        java.util.List<de.mhus.vance.api.vogon.BranchAction> actions = phase.getPostActions();
        if (actions == null || actions.isEmpty()) return;
        Map<String, Object> params = effectiveParams(process, strategy);
        VogonSubstitutor sub = new VogonSubstitutor(params, state);
        java.util.List<de.mhus.vance.api.vogon.BranchAction> resolved =
                substitutePostActions(actions, sub, output);
        BranchActionExecutor.Ctx execCtx = new BranchActionExecutor.Ctx(
                process, documentService, inboxItemService);
        try {
            BranchActionExecutor.execute(strategy, state, resolved, execCtx);
            persistState(process, state);
            log.info("Vogon id='{}' phase '{}' postActions executed ({} actions)",
                    process.getId(), phase.getName(), resolved.size());
        } catch (RuntimeException e) {
            log.warn("Vogon id='{}' phase '{}' postAction failed: {}",
                    process.getId(), phase.getName(), e.toString());
            state.getFlags().put(phaseFlag(phase.getName(), "failed"), true);
            persistState(process, state);
        }
    }

    /** Extract the last top-level JSON object from a free-form
     *  worker reply. Null when reply is blank or contains no JSON. */
    private @Nullable Map<String, Object> parseJsonReply(@Nullable String reply) {
        if (reply == null || reply.isBlank()) return null;
        String json = de.mhus.vance.shared.util.JsonReplyExtractor.extractLastObject(reply);
        if (json == null) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return parsed;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Substitute {@code ${output.X}} / {@code ${params.X}} /
     *  {@code ${flags.X}} / {@code ${phases.X.…}} into all string
     *  fields of the post-actions. Output is a new list with rendered
     *  records — input list is not mutated. */
    private static java.util.List<de.mhus.vance.api.vogon.BranchAction> substitutePostActions(
            java.util.List<de.mhus.vance.api.vogon.BranchAction> actions,
            VogonSubstitutor sub,
            @Nullable Map<String, Object> output) {
        java.util.List<de.mhus.vance.api.vogon.BranchAction> out =
                new java.util.ArrayList<>(actions.size());
        for (de.mhus.vance.api.vogon.BranchAction a : actions) {
            out.add(substituteOne(a, sub, output));
        }
        return out;
    }

    private static de.mhus.vance.api.vogon.BranchAction substituteOne(
            de.mhus.vance.api.vogon.BranchAction a,
            VogonSubstitutor sub,
            @Nullable Map<String, Object> output) {
        if (a instanceof de.mhus.vance.api.vogon.BranchAction.DocCreateText d) {
            return new de.mhus.vance.api.vogon.BranchAction.DocCreateText(
                    renderTemplate(d.path(), sub, output),
                    renderTemplate(d.content(), sub, output),
                    renderTemplate(d.title(), sub, output),
                    d.tags(),
                    d.overwrite());
        }
        if (a instanceof de.mhus.vance.api.vogon.BranchAction.DocCreateKind d) {
            // If `itemsFromOutput` is set, resolve a list-shaped value
            // from the output and pass it as `items`.
            java.util.List<Map<String, Object>> resolvedItems = d.items();
            if (d.itemsFromOutput() != null && output != null) {
                Object resolved = resolvePath(output, d.itemsFromOutput());
                if (resolved instanceof java.util.List<?> list) {
                    resolvedItems = new java.util.ArrayList<>();
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> mm = (Map<String, Object>) m;
                            resolvedItems.add(mm);
                        } else if (o instanceof String s) {
                            // Bare string → wrap as {text: s}.
                            resolvedItems.add(java.util.Map.of("text", s));
                        }
                    }
                }
            }
            return new de.mhus.vance.api.vogon.BranchAction.DocCreateKind(
                    renderTemplate(d.path(), sub, output),
                    d.kind(),
                    renderTemplate(d.title(), sub, output),
                    d.tags(),
                    resolvedItems,
                    d.itemsFromOutput(),
                    d.overwrite());
        }
        if (a instanceof de.mhus.vance.api.vogon.BranchAction.ListAppend la) {
            return new de.mhus.vance.api.vogon.BranchAction.ListAppend(
                    renderTemplate(la.path(), sub, output),
                    renderTemplate(la.text(), sub, output));
        }
        if (a instanceof de.mhus.vance.api.vogon.BranchAction.DocConcat dc) {
            java.util.List<String> sources = new java.util.ArrayList<>(dc.sources().size());
            for (String s : dc.sources()) sources.add(renderTemplate(s, sub, output));
            return new de.mhus.vance.api.vogon.BranchAction.DocConcat(
                    sources,
                    renderTemplate(dc.target(), sub, output),
                    dc.separator(),
                    renderTemplate(dc.header(), sub, output),
                    renderTemplate(dc.footer(), sub, output),
                    renderTemplate(dc.title(), sub, output));
        }
        if (a instanceof de.mhus.vance.api.vogon.BranchAction.InboxPost ip) {
            return new de.mhus.vance.api.vogon.BranchAction.InboxPost(
                    ip.type(),
                    renderTemplate(ip.title(), sub, output),
                    renderTemplate(ip.body(), sub, output),
                    ip.criticality());
        }
        // Flow-control actions (setFlag / setFlags / notifyParent / …)
        // are passed through unchanged — substitution is for
        // executive payloads.
        return a;
    }

    /** Render a template string with the standard {@code ${X}}
     *  substituator, additionally exposing {@code ${output.X}} from
     *  {@code output}. */
    private static @Nullable String renderTemplate(
            @Nullable String template, VogonSubstitutor sub,
            @Nullable Map<String, Object> output) {
        if (template == null) return null;
        // Apply ${output.X} first by simple inline replace; the
        // VogonSubstitutor handles ${params.X} / ${flags.X} / ${phases.X.…}.
        String rendered = renderOutputRefs(template, output);
        return sub.apply(rendered);
    }

    private static String renderOutputRefs(
            String template, @Nullable Map<String, Object> output) {
        if (output == null || output.isEmpty()) return template;
        // Simple ${output.fieldName} replacement. Supports nested
        // dot-paths: ${output.parent.child}.
        java.util.regex.Matcher m =
                OUTPUT_PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String path = m.group(1);
            Object resolved = resolvePath(output, path);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    resolved == null ? "" : String.valueOf(resolved)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static final java.util.regex.Pattern OUTPUT_PLACEHOLDER =
            java.util.regex.Pattern.compile("\\$\\{output\\.([a-zA-Z0-9_.]+)\\}");

    @SuppressWarnings("unchecked")
    private static @Nullable Object resolvePath(Map<String, Object> root, String path) {
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (cur instanceof Map<?, ?> m) {
                cur = ((Map<String, Object>) m).get(seg);
            } else return null;
            if (cur == null) return null;
        }
        return cur;
    }

    /**
     * Run {@code phase.decider}'s evaluator on {@code reply}; on
     * no-match issue up-to {@code maxCorrections} re-prompts before
     * flagging the phase failed. Returns the executed branch's
     * {@link BranchActionExecutor.Result} (null when the decider
     * never reached COMPLETED).
     */
    private BranchActionExecutor.@Nullable Result evaluateDeciderWithCorrections(
            ThinkProcessDocument process,
            ThinkProcessDocument child,
            StrategySpec strategy,
            StrategyState state,
            PhaseSpec phase,
            String phaseKey,
            @Nullable String initialReply) {
        de.mhus.vance.api.vogon.DeciderSpec decider = phase.getDecider();
        if (decider == null) return null;
        int max = decider.getMaxCorrections() == null ? 2 : decider.getMaxCorrections();
        String reply = initialReply;
        int attempts = 0;
        while (true) {
            DeciderEvaluator.Result r = DeciderEvaluator.evaluate(
                    strategy, state, phase, phaseKey, decider, reply);
            if (r.outcome() == DeciderEvaluator.Outcome.COMPLETED) {
                persistState(process, state);
                log.info("Vogon id='{}' phase '{}' decider COMPLETED token='{}' → {}",
                        process.getId(), phase.getName(), r.chosenToken(),
                        r.branchResult() == null ? "no-op" : r.branchResult().kind());
                return r.branchResult();
            }
            if (attempts >= max) {
                log.warn("Vogon id='{}' phase '{}' decider unmatched after {} "
                                + "correction attempts — failing phase. Last hint: {}",
                        process.getId(), phase.getName(), attempts, r.correctionHint());
                state.getFlags().put(phaseFlag(phase.getName(), "failed"), true);
                persistState(process, state);
                return null;
            }
            attempts++;
            log.info("Vogon id='{}' phase '{}' decider correction {}/{} — re-prompt",
                    process.getId(), phase.getName(), attempts, max);
            try {
                driveWorkerTurn(child, process.getId(),
                        "Your last reply did not match the decider options. "
                                + r.correctionHint());
                reply = readLastAssistantText(process.getTenantId(),
                        process.getSessionId(), child.getId());
            } catch (RuntimeException e) {
                log.warn("Vogon id='{}' phase '{}' correction re-prompt failed: {}",
                        process.getId(), phase.getName(), e.toString());
                state.getFlags().put(phaseFlag(phase.getName(), "failed"), true);
                persistState(process, state);
                return null;
            }
        }
    }

    /**
     * Apply a forced branch from a Scorer/Decider case onto the path
     * and process status. {@code CONTINUE} should never reach this —
     * the caller skips the call in that case.
     */
    private void applyBranchOverride(
            ThinkProcessDocument process,
            StrategySpec strategy,
            StrategyState state,
            PhaseSpec donePhase,
            BranchActionExecutor.Result override) {
        switch (override.kind()) {
            case CONTINUE -> { /* unreachable — caller filters */ }
            case JUMPED -> {
                // Path already mutated by BranchActionExecutor; persist
                // and trigger the next turn so the new phase is picked up.
                persistState(process, state);
                log.info("Vogon id='{}' phase '{}' scorer JUMPED → '{}'",
                        process.getId(), donePhase.getName(), override.detail());
                eventEmitter.scheduleTurn(process.getId());
            }
            case EXIT_LOOP -> {
                BranchAction.ExitOutcome outcome = override.exitOutcome() == null
                        ? BranchAction.ExitOutcome.OK : override.exitOutcome();
                PhaseAdvancer.Outcome out = PhaseAdvancer.forceExitLoop(
                        strategy, state, outcome);
                persistState(process, state);
                log.info("Vogon id='{}' phase '{}' scorer EXIT_LOOP {} → outer outcome {}",
                        process.getId(), donePhase.getName(), outcome, out);
                handleAdvancerOutcome(process, strategy, state, out);
            }
            case EXIT_STRATEGY -> {
                BranchAction.ExitOutcome outcome = override.exitOutcome() == null
                        ? BranchAction.ExitOutcome.OK : override.exitOutcome();
                state.getCurrentPhasePath().clear();
                state.setStrategyComplete(true);
                persistState(process, state);
                log.info("Vogon id='{}' phase '{}' scorer EXIT_STRATEGY {}",
                        process.getId(), donePhase.getName(), outcome);
                thinkProcessService.closeProcess(process.getId(),
                        outcome == BranchAction.ExitOutcome.FAIL
                                ? CloseReason.STALE : CloseReason.DONE);
            }
            case ESCALATED -> {
                // Sub-strategy spawn comes with §2.8 escalation block
                // wiring. For now log and fail the process so the parent
                // sees a clean STALE event with the escalation reason.
                log.warn("Vogon id='{}' phase '{}' scorer ESCALATED → '{}' "
                                + "(sub-strategy spawn not yet implemented)",
                        process.getId(), donePhase.getName(), override.detail());
                state.getFlags().put("__scorerEscalation__", override.detail());
                persistState(process, state);
                thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            }
            case PAUSED -> {
                log.info("Vogon id='{}' phase '{}' scorer PAUSED reason='{}'",
                        process.getId(), donePhase.getName(), override.detail());
                persistState(process, state);
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
            }
        }
    }

    private void handleAdvancerOutcome(
            ThinkProcessDocument process,
            StrategySpec strategy,
            StrategyState state,
            PhaseAdvancer.Outcome out) {
        switch (out) {
            case CONTINUE -> eventEmitter.scheduleTurn(process.getId());
            case STRATEGY_DONE -> {
                log.info("Vogon id='{}' strategy '{}' done after scorer-forced exit",
                        process.getId(), strategy.getName());
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            }
            case STRATEGY_FAILED, ESCALATION_NEEDED -> {
                log.warn("Vogon id='{}' strategy '{}' STALE after scorer-forced exit "
                                + "(outcome={})",
                        process.getId(), strategy.getName(), out);
                thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            }
        }
    }

    private void advanceToNext(
            ThinkProcessDocument process,
            StrategySpec strategy,
            StrategyState state,
            PhaseSpec done) {
        PhaseAdvancer.Outcome outcome = PhaseAdvancer.advanceAfter(strategy, state, done);
        persistState(process, state);
        switch (outcome) {
            case CONTINUE -> log.info("Vogon id='{}' phase '{}' DONE → leaf '{}'",
                    process.getId(), done.getName(), currentLeafPhaseName(state));
            case STRATEGY_DONE -> log.info("Vogon id='{}' strategy '{}' all phases done",
                    process.getId(), strategy.getName());
            case STRATEGY_FAILED -> {
                log.warn("Vogon id='{}' strategy '{}' loop EXIT_FAIL — failing process",
                        process.getId(), strategy.getName());
                thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            }
            case ESCALATION_NEEDED -> {
                // Phase B: §2.8 escalation block isn't wired yet, so we
                // notify the parent and fail the process with the
                // loopExhausted reason. The §2.8 implementation will
                // intercept this branch and run the matching rule.
                log.warn("Vogon id='{}' loop exhausted in strategy '{}' — "
                                + "no escalation block yet, notifying parent",
                        process.getId(), strategy.getName());
                thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            }
        }
    }

    // ──────────────────── summarizeForParent ────────────────────

    @Override
    public ParentReport summarizeForParent(
            ThinkProcessDocument process, ProcessEventType eventType) {
        StrategyState state;
        try {
            state = loadState(process);
        } catch (RuntimeException e) {
            return ParentReport.of("Vogon process " + process.getId()
                    + " status=" + eventType.name().toLowerCase());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType.name());
        payload.put("strategy", state.getStrategy());
        payload.put("phasesCompleted", state.getPhaseHistory());
        payload.put("flags", state.getFlags());

        StringBuilder sb = new StringBuilder();
        sb.append("Vogon strategy '").append(state.getStrategy())
                .append("' (").append(eventType.name().toLowerCase())
                .append(") — ").append(state.getPhaseHistory().size())
                .append(" phase(s) completed.");
        for (String phaseName : state.getPhaseHistory()) {
            Map<String, Object> artifacts = state.getPhaseArtifacts().get(phaseName);
            Object result = artifacts == null ? null : artifacts.get("result");
            if (result instanceof String s && !s.isBlank()) {
                sb.append("\n\n--- phase ").append(phaseName).append(" ---\n").append(s);
            }
        }
        return new ParentReport(sb.toString(), payload);
    }

    // ──────────────────── State persistence ────────────────────

    @SuppressWarnings("unchecked")
    private StrategyState loadState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        if (p == null) return StrategyState.builder().build();
        Object raw = p.get(STATE_KEY);
        if (raw == null) return StrategyState.builder().build();
        return objectMapper.convertValue(raw, StrategyState.class);
    }

    private void persistState(ThinkProcessDocument process, StrategyState state) {
        Map<String, Object> p = process.getEngineParams() == null
                ? new LinkedHashMap<>() : process.getEngineParams();
        Map<String, Object> serialized = objectMapper.convertValue(state, Map.class);
        p.put(STATE_KEY, serialized);
        process.setEngineParams(p);
        // Persist via service so optimistic-locking is honoured.
        thinkProcessService.replaceEngineParams(process.getId(), p);
    }

    // ──────────────────── Helpers ────────────────────

    private @Nullable PhaseSpec findPhase(StrategySpec strategy, @Nullable String phaseName) {
        if (phaseName == null) return null;
        for (PhaseSpec p : strategy.getPhases()) {
            if (phaseName.equals(p.getName())) return p;
        }
        return null;
    }

    // ──────────────────── Plan snapshot ────────────────────

    /**
     * Builds a {@link de.mhus.vance.api.progress.PlanPayload} from the
     * strategy + current state and pushes it to the user-progress
     * side-channel. Each phase becomes a child of the strategy root;
     * status is derived from {@code phaseHistory}, {@code currentPhasePath},
     * and {@code pendingCheckpoint}.
     */
    private void emitPlanSnapshot(
            ThinkProcessDocument process,
            StrategySpec strategy,
            StrategyState state) {
        if (process.getId() == null) return;
        java.util.List<de.mhus.vance.api.progress.PlanNode> phaseNodes =
                new java.util.ArrayList<>();
        java.util.Set<String> historySet = new java.util.HashSet<>(state.getPhaseHistory());
        String current = currentLeafPhaseName(state);
        boolean blocked = state.getPendingCheckpoint() != null;
        for (PhaseSpec p : strategy.getPhases()) {
            String status;
            if (historySet.contains(p.getName())) {
                status = "done";
            } else if (p.getName().equals(current)) {
                status = blocked ? "blocked" : "running";
            } else {
                status = "pending";
            }
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            if (p.getWorker() != null) meta.put("worker", p.getWorker());
            if (p.getCheckpoint() != null) meta.put("hasCheckpoint", true);
            if (p.getGate() != null) meta.put("hasGate", true);
            String spawnedId = state.getWorkerProcessIds().get(p.getName());
            if (spawnedId != null) meta.put("spawnedProcessId", spawnedId);
            if (status.equals("blocked") && state.getPendingCheckpoint() != null) {
                meta.put("inboxItemId", state.getPendingCheckpoint().getInboxItemId());
            }
            phaseNodes.add(de.mhus.vance.api.progress.PlanNode.builder()
                    .id(p.getName())
                    .kind("phase")
                    .title(p.getName())
                    .status(status)
                    .meta(meta)
                    .build());
        }
        String rootStatus = state.isStrategyComplete()
                ? "done"
                : (current == null ? "pending" : (blocked ? "blocked" : "running"));
        de.mhus.vance.api.progress.PlanNode rootNode =
                de.mhus.vance.api.progress.PlanNode.builder()
                        .id(state.getStrategy())
                        .kind("strategy")
                        .title(state.getStrategy())
                        .status(rootStatus)
                        .children(phaseNodes)
                        .build();
        progressEmitter.emitPlan(
                process,
                de.mhus.vance.api.progress.PlanPayload.builder()
                        .rootNode(rootNode)
                        .build());
    }

    private static boolean isFlagTrue(StrategyState state, String flag) {
        Object v = state.getFlags().get(flag);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return v != null;
    }

    /**
     * Effective params = strategy.paramDefaults overlaid with the
     * caller params from {@code engineParams}. Caller wins on
     * conflicts. Strategy state itself is excluded. Plus
     * {@code process.goal} is injected as {@code params.goal} —
     * {@code process_create} stores the goal at the top level of
     * the process document, but strategy templates conventionally
     * reference it as {@code ${params.goal}}, so we expose both
     * forms transparently. An explicit {@code engineParams.goal}
     * wins if both are set.
     */
    private Map<String, Object> effectiveParams(
            ThinkProcessDocument process, StrategySpec strategy) {
        Map<String, Object> out = new LinkedHashMap<>(strategy.getParamDefaults());
        // Inject process.goal as the default goal — overridable by
        // an explicit engineParams.goal below.
        if (process.getGoal() != null && !process.getGoal().isBlank()) {
            out.put("goal", process.getGoal());
        }
        Map<String, Object> ep = process.getEngineParams();
        if (ep != null) {
            Set<String> reserved = new LinkedHashSet<>();
            reserved.add(STATE_KEY);
            reserved.add(STRATEGY_KEY);
            for (Map.Entry<String, Object> e : ep.entrySet()) {
                if (reserved.contains(e.getKey())) continue;
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static @Nullable String stringParam(
            ThinkProcessDocument process, String key, @Nullable String fallback) {
        Map<String, Object> p = process.getEngineParams();
        Object v = p == null ? null : p.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    /**
     * Resolves the project the process is running in — needed by the
     * strategy cascade so per-project overrides get a chance. Returns
     * {@code null} when no session is bound, in which case the cascade
     * collapses to {@code _vance} → classpath.
     */
    private @Nullable String resolveProjectId(ThinkProcessDocument process) {
        String sessionId = process.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return null;
        return sessionService.findBySessionId(sessionId)
                .map(s -> s.getProjectId())
                .filter(p -> p != null && !p.isBlank())
                .orElse(null);
    }
}
