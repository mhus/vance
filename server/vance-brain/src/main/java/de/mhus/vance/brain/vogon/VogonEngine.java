package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.vogon.CheckpointSpec;
import de.mhus.vance.api.vogon.CheckpointType;
import de.mhus.vance.api.vogon.GateSpec;
import de.mhus.vance.api.vogon.PhaseSpec;
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

    /** {@code engineParams[STRATEGY_KEY]} — strategy name to run. */
    public static final String STRATEGY_KEY = "strategy";

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

    private static final String SETTINGS_REF_TYPE = "tenant";

    private final BundledStrategyRegistry strategyRegistry;
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
        String strategyName = stringParam(process, STRATEGY_KEY, null);
        if (strategyName == null) {
            throw new IllegalStateException(
                    "Vogon.start requires engineParams.strategy — id='"
                            + process.getId() + "'");
        }
        StrategySpec strategy = strategyRegistry.find(strategyName)
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown strategy '" + strategyName + "'"));
        if (strategy.getPhases().isEmpty()) {
            throw new IllegalStateException(
                    "Strategy '" + strategyName + "' has no phases");
        }
        StrategyState state = StrategyState.builder()
                .strategy(strategy.getName())
                .strategyVersion(strategy.getVersion())
                .currentPhaseName(strategy.getPhases().get(0).getName())
                .build();
        persistState(process, state);
        log.info("Vogon.start tenant='{}' session='{}' id='{}' strategy='{}' phase='{}'",
                process.getTenantId(), process.getSessionId(), process.getId(),
                strategy.getName(), state.getCurrentPhaseName());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
        // Trigger first turn on Vogon's own lane.
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Vogon.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
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
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.STOPPED);
    }

    // ──────────────────── runTurn ────────────────────

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        StrategyState initialState = loadState(process);
        StrategySpec strategy = strategyRegistry.find(initialState.getStrategy())
                .orElseThrow(() -> new IllegalStateException(
                        "Strategy '" + initialState.getStrategy() + "' missing at runtime"));
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
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.DONE);
                return;
            }

            // 3. Pick the current phase. Null = no current → done.
            PhaseSpec phase = findPhase(strategy, state.getCurrentPhaseName());
            if (phase == null) {
                state.setStrategyComplete(true);
                persistState(process, state);
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.DONE);
                return;
            }

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
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.STALE);
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
            spawnAndAwaitWorker(process, ctx, phase, params, state, sub);
            // Re-load after spawn — flag was set inside.
            state = loadState(process);
            workerDone = isFlagTrue(state, phaseFlag(phaseName, "completed"));
            if (!workerDone) {
                // Worker FAILED — flag failed=true is set; mark process STALE.
                if (isFlagTrue(state, phaseFlag(phaseName, "failed"))) {
                    log.warn("Vogon id='{}' phase '{}' worker FAILED — process stale",
                            process.getId(), phaseName);
                    thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.STALE);
                    return;
                }
                // Defensive: shouldn't happen with synchronous spawn,
                // but yield rather than loop.
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
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
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
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
    private void spawnAndAwaitWorker(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
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
            return;
        }
        String steerContent = sub.apply(phase.getWorkerInput());
        ThinkProcessDocument child;
        try {
            AppliedRecipe applied = recipeResolver.apply(
                    process.getTenantId(), ctx.projectId(), recipeName, /*params*/ null);
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
                    applied.intentCorrection(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools());
            state.getWorkerProcessIds().put(phase.getName(), child.getId());
            persistState(process, state);
            thinkEngineServiceProvider.getObject().start(child);
            log.info("Vogon id='{}' phase '{}' spawned worker child='{}' recipe='{}'",
                    process.getId(), phase.getName(), child.getId(), applied.name());
        } catch (RuntimeException e) {
            log.warn("Vogon id='{}' phase '{}' spawn failed: {}",
                    process.getId(), phase.getName(), e.toString());
            state.getFlags().put(phaseFlag(phase.getName(), "failed"), true);
            persistState(process, state);
            return;
        }

        // Synchronous turn drive.
        try {
            driveWorkerTurn(child, process.getId(), steerContent);
            String reply = readLastAssistantText(process.getTenantId(),
                    process.getSessionId(), child.getId());
            Map<String, Object> artifact = new LinkedHashMap<>();
            if (reply != null) artifact.put("result", reply);
            state.getPhaseArtifacts().put(phase.getName(), artifact);
            state.getFlags().put(phaseFlag(phase.getName(), "completed"), true);
            persistState(process, state);
            log.info("Vogon id='{}' phase '{}' worker DONE — {} chars captured",
                    process.getId(), phase.getName(),
                    reply == null ? 0 : reply.length());
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
                phaseFlag(state.getCurrentPhaseName(), "checkpointAnswered"));
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

    private void advanceToNext(
            ThinkProcessDocument process,
            StrategySpec strategy,
            StrategyState state,
            PhaseSpec done) {
        state.getPhaseHistory().add(done.getName());
        int idx = -1;
        for (int i = 0; i < strategy.getPhases().size(); i++) {
            if (strategy.getPhases().get(i).getName().equals(done.getName())) {
                idx = i;
                break;
            }
        }
        if (idx < 0 || idx + 1 >= strategy.getPhases().size()) {
            state.setCurrentPhaseName(null);
            state.setStrategyComplete(true);
            log.info("Vogon id='{}' strategy '{}' all phases done",
                    process.getId(), strategy.getName());
        } else {
            String nextName = strategy.getPhases().get(idx + 1).getName();
            state.setCurrentPhaseName(nextName);
            log.info("Vogon id='{}' phase '{}' DONE → next phase '{}'",
                    process.getId(), done.getName(), nextName);
        }
        persistState(process, state);
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
     * status is derived from {@code phaseHistory}, {@code currentPhaseName},
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
        String current = state.getCurrentPhaseName();
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
}
