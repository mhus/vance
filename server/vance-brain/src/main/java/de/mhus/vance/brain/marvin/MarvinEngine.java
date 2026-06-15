package de.mhus.vance.brain.marvin;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.marvin.ConcludeOutput;
import de.mhus.vance.api.marvin.NewTaskSpec;
import de.mhus.vance.api.marvin.NodeStatus;
import de.mhus.vance.api.marvin.PhaseIteration;
import de.mhus.vance.api.marvin.PostActionSpec;
import de.mhus.vance.api.marvin.PostChildrenOutput;
import de.mhus.vance.api.marvin.RecipeCall;
import de.mhus.vance.api.marvin.ReflectOutput;
import de.mhus.vance.api.marvin.ScopeOutput;
import de.mhus.vance.api.marvin.TaskKind;
import de.mhus.vance.api.marvin.UserInputSpec;
import de.mhus.vance.api.marvin.ValidateOutput;
import de.mhus.vance.api.marvin.WorkerPhase;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeLoader;
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
import de.mhus.vance.shared.marvin.MarvinNodeDocument;
import de.mhus.vance.shared.marvin.MarvinNodeService;
import de.mhus.vance.shared.marvin.MarvinNodeService.NodeSpec;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * Marvin v2 — autonomous-worker deep-think engine.
 *
 * <p>Every WORKER node walks a five-phase state-machine
 * (SCOPE → REFLECT → POST_CHILDREN → CONCLUDE → VALIDATE) with
 * bounded iteration caps. The tree IS the plan: every LLM call
 * receives a live snapshot of the entire tree, rendered by
 * {@link PlanSnapshotRenderer}.
 *
 * <p>Specialised recipes (web-research, analyze, code-read …) are
 * never spawned directly as WORKER nodes. Instead the worker LLM
 * decides in SCOPE/REFLECT to {@code CALL_RECIPE} — Marvin spawns
 * the sub-process synchronously, captures its reply, and appends
 * it as a USER message before re-entering REFLECT. The specialist
 * recipe runs in its native mode without the Marvin output
 * contract being forced on it.
 *
 * <p>See {@code specification/marvin-engine.md} for the full design.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarvinEngine implements ThinkEngine {

    public static final String NAME = "marvin";
    public static final String VERSION = "2.0.0";

    /** Recipe-name of the worker LLM that drives every WORKER node.
     *  When CALL_RECIPE targets a recipe other than this, that
     *  recipe runs WITHOUT the phase contract being layered on top. */
    public static final String WORKER_RECIPE_NAME = "marvin-worker";

    /** Document-cascade path for the marvin-worker system prompt. */
    private static final String WORKER_SYSTEM_PROMPT_PATH =
            "prompts/marvin-worker-system.md";

    /** Fallback embedded system prompt — bundled marvin-worker
     *  recipe normally supplies a richer one via the document
     *  cascade. */
    private static final String FALLBACK_WORKER_SYSTEM_PROMPT = """
            You are a Marvin worker. Each turn you receive a Phase
            instruction and a live Plan snapshot. You respond with a
            single JSON object matching the phase's schema. No prose
            outside the JSON.

            Available phases:
              SCOPE   — initial decision (CALL_RECIPE | PROCEED_TO_CONCLUDE
                        | NEEDS_SUBTASKS | NEEDS_USER_INPUT | BLOCKED_BY_PROBLEM)
              REFLECT — after a CALL_RECIPE; same actions, capped at 3 calls
              POST_CHILDREN — children done; PROCEED_TO_CONCLUDE | NEEDS_SUBTASKS | BLOCKED_BY_PROBLEM
              CONCLUDE — produce the final markdown answer
              VALIDATE — critique the candidate (PASS | RETRY_CONCLUDE | NEED_MORE_DATA | HARD_FAIL)

            Before deciding, ALWAYS check the LIVE PLAN:
              - if your goal is already covered by another node, do not duplicate it
              - stay in your lane: do not spawn work that belongs to a sibling
              - prefer doing the work yourself (PROCEED_TO_CONCLUDE) over decomposing.
            """;

    /** Marvin's discovery tool cut — same as v1; available only to
     *  the very rare case where marvin-worker itself runs without a
     *  recipe override. The bulk of tool use happens inside
     *  CALL_RECIPE sub-processes. */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "whoami",
            "current_time",
            "recipe_list",
            "recipe_describe",
            "manual_list",
            "manual_read",
            "web_search",
            "web_fetch");

    private final MarvinNodeService nodeService;
    private final MarvinProperties properties;
    private final InboxItemService inboxItemService;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final RecipeResolver recipeResolver;
    private final RecipeLoader recipeLoader;
    private final PhaseOutputParser phaseParser;
    private final PlanSnapshotRenderer planSnapshotRenderer;
    private final de.mhus.vance.brain.progress.LlmCallTracker llmCallTracker;
    private final de.mhus.vance.brain.progress.ProgressEmitter progressEmitter;
    private final de.mhus.vance.brain.thinkengine.EnginePromptResolver enginePromptResolver;
    private final de.mhus.vance.brain.thinkengine.SystemPromptComposer composer;
    private final de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory;
    private final ObjectMapper objectMapper;
    private final ProcessEventEmitter eventEmitter;
    private final LaneScheduler laneScheduler;
    private final DocumentExpander documentExpander;
    private final de.mhus.vance.shared.workspace.WorkspaceService workspaceService;
    private final de.mhus.vance.shared.document.DocumentService documentService;
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;

    // ──────────────────── Metadata ────────────────────

    @Override public String name() { return NAME; }
    @Override public String title() { return "Marvin (Deep-Think)"; }
    @Override public String description() {
        return "Autonomous-worker deep-think engine. Every node runs the "
                + "5-phase state-machine (SCOPE → REFLECT → POST_CHILDREN → "
                + "CONCLUDE → VALIDATE). The tree grows dynamically; "
                + "specialist recipes are invoked via CALL_RECIPE.";
    }
    @Override public String version() { return VERSION; }
    @Override public Set<String> allowedTools() { return ALLOWED_TOOLS; }
    @Override public boolean asyncSteer() { return true; }

    /**
     * Parent-report path. The deliverable of a Marvin process is the
     * root WORKER's CONCLUDE result; fall back to a child-roll-up if
     * the root hasn't reached DONE.
     */
    @Override
    public ParentReport summarizeForParent(
            ThinkProcessDocument process, ProcessEventType eventType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Optional<MarvinNodeDocument> rootOpt = nodeService.findRoot(process.getId());
        if (rootOpt.isEmpty()) {
            return new ParentReport(
                    "Marvin process '" + process.getId() + "' has no tree yet.",
                    payload);
        }
        MarvinNodeDocument root = rootOpt.get();
        List<MarvinNodeDocument> rootChildren =
                nodeService.findChildren(process.getId(), root.getId());

        List<MarvinNodeDocument> failedChildren = new ArrayList<>();
        for (MarvinNodeDocument c : rootChildren) {
            if (c.getStatus() == NodeStatus.FAILED) failedChildren.add(c);
        }
        if (!failedChildren.isEmpty()) {
            List<Map<String, Object>> failedPayload = new ArrayList<>();
            for (MarvinNodeDocument c : failedChildren) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("nodeId", c.getId());
                entry.put("taskKind", c.getTaskKind() == null
                        ? null : c.getTaskKind().name());
                entry.put("goal", c.getGoal());
                if (c.getFailureReason() != null) {
                    entry.put("failureReason", c.getFailureReason());
                }
                failedPayload.add(entry);
            }
            payload.put("failedChildCount", failedChildren.size());
            payload.put("failedChildren", failedPayload);
        }

        // Primary path: root's own result.
        Map<String, Object> rootArtifacts = root.getArtifacts();
        if (rootArtifacts != null) {
            Object res = rootArtifacts.get("result");
            if (res instanceof String s && !s.isBlank()) {
                payload.put("rootNodeId", root.getId());
                payload.put("nodeCount",
                        nodeService.listAll(process.getId()).size());
                return new ParentReport(
                        appendFailureBlock(s, failedChildren), payload);
            }
        }

        // Fallback: concatenate child results.
        int doneChildren = 0;
        for (MarvinNodeDocument c : rootChildren) {
            if (c.getStatus() == NodeStatus.DONE) doneChildren++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Marvin tree finished (").append(eventType.name().toLowerCase())
                .append(") — ").append(doneChildren).append(" of ")
                .append(rootChildren.size())
                .append(" child(ren) succeeded:\n");
        int included = 0;
        for (MarvinNodeDocument c : rootChildren) {
            if (c.getStatus() != NodeStatus.DONE) continue;
            Map<String, Object> a = c.getArtifacts();
            Object res = a == null ? null : a.get("result");
            if (res instanceof String s && !s.isBlank()) {
                sb.append("\n--- ").append(c.getTaskKind())
                        .append(" / ").append(abbrev(c.getGoal())).append(" ---\n")
                        .append(s).append('\n');
                included++;
            }
        }
        if (included == 0 && failedChildren.size() < rootChildren.size()) {
            sb.append("\n(no child produced a textual result)");
        }
        payload.put("includedChildCount", included);
        payload.put("doneChildCount", doneChildren);
        payload.put("nodeCount", nodeService.listAll(process.getId()).size());
        return new ParentReport(
                appendFailureBlock(sb.toString(), failedChildren), payload);
    }

    private static String appendFailureBlock(
            String mainText, List<MarvinNodeDocument> failures) {
        if (failures.isEmpty()) return mainText;
        StringBuilder sb = new StringBuilder(mainText);
        if (!mainText.endsWith("\n")) sb.append('\n');
        sb.append("\n--- Failed nodes (").append(failures.size()).append(") ---\n");
        for (MarvinNodeDocument c : failures) {
            sb.append("- ");
            if (c.getTaskKind() != null) sb.append(c.getTaskKind()).append(" / ");
            sb.append(abbrev(c.getGoal()));
            if (c.getFailureReason() != null && !c.getFailureReason().isBlank()) {
                sb.append(" — ").append(c.getFailureReason());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Marvin.start tenant='{}' session='{}' id='{}' goal='{}'",
                process.getTenantId(), process.getSessionId(), process.getId(),
                abbrev(process.getGoal()));
        String goal = process.getGoal();
        if (goal == null || goal.isBlank()) {
            throw new IllegalStateException(
                    "Marvin.start requires process.goal — id='" + process.getId() + "'");
        }
        // The root is ALWAYS a WORKER in v2 — there's no separate
        // PLAN node anymore. The worker's SCOPE decides whether to
        // call recipes, decompose, or answer directly.
        nodeService.createRoot(
                process.getTenantId(), process.getId(), goal,
                TaskKind.WORKER, new LinkedHashMap<>());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Marvin.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Marvin.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        if (message instanceof SteerMessage.UserChatInput uci) {
            log.info("Marvin id='{}' steer (async) from='{}' content='{}'",
                    process.getId(), uci.fromUser(), abbrev(uci.content()));
        }
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Marvin.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    // ──────────────────── runTurn ────────────────────

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            runTurnInner(process, ctx);
        } finally {
            try {
                emitPlanSnapshot(process);
            } catch (RuntimeException pe) {
                log.debug("Marvin id='{}' plan-snapshot push failed: {}",
                        process.getId(), pe.toString());
            }
        }
    }

    private void runTurnInner(ThinkProcessDocument process, ThinkEngineContext ctx) {
        try {
            List<SteerMessage> drained = ctx.drainPending();
            for (SteerMessage msg : drained) {
                consumePending(process, msg);
            }

            // Reactivate any awaitingPostChildren parent whose children
            // have all reached a terminal status. Without this sweep
            // a NEEDS_SUBTASKS parent would stay DONE forever and the
            // POST_CHILDREN phase never run.
            reactivatePostChildrenParents(process);

            Optional<MarvinNodeDocument> nextOpt =
                    nodeService.findNextActionableNode(
                            process.getId(), process.getEngineParams());
            if (nextOpt.isEmpty()) {
                finalizeIdle(process);
                return;
            }
            if (nodeBudgetExceeded(process)) {
                log.warn("Marvin id='{}' tree exceeded maxTreeNodes={} — stopping",
                        process.getId(), properties.getMaxTreeNodes());
                nodeService.markFailed(nextOpt.get(),
                        "tree exceeded maxTreeNodes=" + properties.getMaxTreeNodes());
                finalizeIdle(process);
                return;
            }

            MarvinNodeDocument node = nextOpt.get();
            boolean parked = executeNode(process, ctx, node);
            if (parked) {
                finalizeIdle(process);
                return;
            }
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
            eventEmitter.scheduleTurn(process.getId());
        } catch (RuntimeException e) {
            log.warn("Marvin runTurn failed id='{}': {}",
                    process.getId(), e.toString(), e);
            thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            throw e;
        }
    }

    /**
     * Flips DONE nodes carrying {@code awaitingPostChildren=true} back
     * to {@code PENDING} with {@code currentPhase=POST_CHILDREN} once
     * all their children have terminated. Called at the top of every
     * runTurn so the DFS picks the resurrected node up in the same
     * lane iteration.
     *
     * <p>Without this sweep the parent's POST_CHILDREN phase would
     * never fire — the parent was made DONE on NEEDS_SUBTASKS to let
     * the DFS descend into the spawned children, but nothing else
     * would re-activate it.
     */
    private void reactivatePostChildrenParents(ThinkProcessDocument process) {
        List<MarvinNodeDocument> all = nodeService.listAll(process.getId());
        for (MarvinNodeDocument n : all) {
            if (!n.isAwaitingPostChildren()) continue;
            if (n.getStatus() != NodeStatus.DONE) continue;
            if (n.getId() == null) continue;
            if (!nodeService.allChildrenTerminal(process.getId(), n.getId())) continue;
            log.info("Marvin id='{}' reactivating node='{}' for POST_CHILDREN — children terminal",
                    process.getId(), n.getId());
            n.setAwaitingPostChildren(false);
            n.setStatus(NodeStatus.PENDING);
            n.setCurrentPhase(WorkerPhase.POST_CHILDREN);
            nodeService.save(n);
        }
    }

    private void consumePending(ThinkProcessDocument process, SteerMessage msg) {
        switch (msg) {
            case SteerMessage.ProcessEvent pe -> handleProcessEvent(process, pe);
            case SteerMessage.InboxAnswer ia -> handleInboxAnswer(process, ia);
            case SteerMessage.UserChatInput uci ->
                    log.debug("Marvin id='{}' ignoring UserChatInput from='{}' — Marvin doesn't talk directly",
                            process.getId(), uci.fromUser());
            case SteerMessage.ToolResult tr ->
                    log.debug("Marvin id='{}' ignoring ToolResult tool='{}'",
                            process.getId(), tr.toolName());
            case SteerMessage.ExternalCommand ec ->
                    log.info("Marvin id='{}' external command '{}' — not yet routed",
                            process.getId(), ec.command());
            case SteerMessage.PeerEvent pe ->
                    log.debug("Marvin id='{}' ignoring PeerEvent type='{}'",
                            process.getId(), pe.type());
        }
    }

    /**
     * Handles a child process's lifecycle event. Two channels:
     *
     * <ul>
     *   <li>Sub-processes spawned via CALL_RECIPE are tracked in
     *       {@code node.calledSubProcessIds}; we don't act on their
     *       events here because the calling worker's CALL_RECIPE
     *       handler awaits them synchronously.</li>
     *   <li>NEEDS_SUBTASKS-spawned WORKER children inherit the
     *       state-machine and report DONE/FAILED themselves; the
     *       POST_CHILDREN trigger fires once all of a node's
     *       children are terminal (checked at runTurn-top via the
     *       {@code awaitingPostChildren} flag).</li>
     * </ul>
     */
    private void handleProcessEvent(
            ThinkProcessDocument process, SteerMessage.ProcessEvent event) {
        Optional<MarvinNodeDocument> bySpawn =
                nodeService.findBySpawnedProcessId(event.sourceProcessId());
        if (bySpawn.isPresent()) {
            // Spawned-by-WORKER (a recursive Marvin sub-process or
            // EXPAND-promoted child). Most of these don't apply in
            // v2 since WORKER nodes drive themselves via the
            // state-machine; we keep the hook for engines that
            // genuinely spawn children — log and move on.
            log.debug("Marvin id='{}' ProcessEvent for spawned node='{}' type={}",
                    process.getId(), bySpawn.get().getId(), event.type());
        }
        // The POST_CHILDREN trigger is checked in runTurn rather than
        // here — a child's DONE event is also queued, which means
        // runTurn will see it and re-evaluate the parent.
    }

    private void handleInboxAnswer(
            ThinkProcessDocument process, SteerMessage.InboxAnswer answer) {
        Optional<MarvinNodeDocument> nodeOpt =
                nodeService.findByInboxItemId(answer.inboxItemId());
        if (nodeOpt.isEmpty()) {
            log.warn("Marvin id='{}' got InboxAnswer for unknown item='{}'",
                    process.getId(), answer.inboxItemId());
            return;
        }
        MarvinNodeDocument node = nodeOpt.get();
        switch (answer.answer().getOutcome()) {
            case DECIDED -> {
                Map<String, Object> artifacts = new LinkedHashMap<>();
                artifacts.put("userAnswer",
                        answer.answer().getValue() == null
                                ? Map.of() : answer.answer().getValue());
                artifacts.put("answeredBy", answer.answer().getAnsweredBy());
                nodeService.markDone(node, artifacts);
                log.info("Marvin id='{}' user-input DONE node='{}' item='{}'",
                        process.getId(), node.getId(), answer.inboxItemId());
            }
            case INSUFFICIENT_INFO, UNDECIDABLE -> {
                String reason = answer.answer().getReason() == null
                        ? answer.answer().getOutcome().name()
                        : answer.answer().getOutcome().name() + ": "
                                + answer.answer().getReason();
                nodeService.markFailed(node, reason);
                log.info("Marvin id='{}' user-input {} node='{}' item='{}': {}",
                        process.getId(), answer.answer().getOutcome(),
                        node.getId(), answer.inboxItemId(),
                        answer.answer().getReason());
            }
        }
    }

    // ──────────────────── Node dispatch ────────────────────

    /**
     * Returns {@code true} if the node parked (RUNNING/WAITING)
     * or completed fully; {@code false} if synchronous and the
     * lane should pick the next node immediately.
     */
    private boolean executeNode(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            MarvinNodeDocument node) {
        log.info("Marvin id='{}' executing node='{}' kind={} pos={} goal='{}'",
                process.getId(), node.getId(), node.getTaskKind(),
                node.getPosition(), abbrev(node.getGoal()));
        return switch (node.getTaskKind()) {
            case WORKER -> driveWorkerNode(process, ctx, node);
            case EXPAND_FROM_DOC -> {
                nodeService.markRunning(node);
                runExpandFromDoc(process, ctx, node);
                yield false;
            }
            case USER_INPUT -> {
                nodeService.markRunning(node);
                yield runUserInput(process, ctx, node);
            }
        };
    }

    // ════════════════════════════════════════════════════════════
    //                       WORKER STATE MACHINE
    // ════════════════════════════════════════════════════════════

    /**
     * Drives a WORKER node through (a portion of) its phase cycle
     * in one runTurn. Returns {@code true} if the node parked
     * (CALL_RECIPE awaiting sync-completed reply not applicable —
     * we await inline; NEEDS_SUBTASKS spawns children and parks;
     * NEEDS_USER_INPUT spawns inbox-item and parks).
     *
     * <p>The phase cursor lives in {@code node.currentPhase} so a
     * node can be resumed after process restart. The actual LLM
     * call happens in {@code runPhase} below; the dispatch here
     * is just bookkeeping + transition routing.
     */
    private boolean driveWorkerNode(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            MarvinNodeDocument node) {
        // Resume or initialise the phase cursor. A fresh PENDING node
        // without phase starts at SCOPE; a reactivated node (set to
        // PENDING by reactivatePostChildrenParents) already carries
        // its target phase and must NOT be reset to SCOPE.
        if (node.getCurrentPhase() == null) {
            nodeService.setCurrentPhase(node, WorkerPhase.SCOPE);
            node.setCurrentPhase(WorkerPhase.SCOPE);
        }
        nodeService.markRunning(node);

        MarvinNodeStateMachine.Caps caps = readCaps(process);
        MarvinNodeStateMachine.Counters counters = new MarvinNodeStateMachine.Counters(
                node.getReflectIter(), node.getValidateIter(), node.getConcludeRetries());

        try {
            // Loop drives the worker through as many synchronous
            // phases as possible. Yields when a phase result requires
            // an external wait (NEEDS_SUBTASKS, NEEDS_USER_INPUT).
            while (true) {
                WorkerPhase phase = node.getCurrentPhase();
                if (phase == null) phase = WorkerPhase.SCOPE;
                log.info("Marvin id='{}' node='{}' phase={} iter(reflect={}/val={}/conc={})",
                        process.getId(), node.getId(), phase,
                        counters.reflectIter(), counters.validateIter(), counters.concludeRetries());

                MarvinNodeStateMachine.Transition trans =
                        runPhase(process, ctx, node, phase, caps, counters);

                if (trans instanceof MarvinNodeStateMachine.ContinueWithPhase cont) {
                    counters = cont.newCounters();
                    persistCounters(node, counters);
                    nodeService.setCurrentPhase(node, cont.nextPhase());
                    node.setCurrentPhase(cont.nextPhase());
                    // Pass the hint into the next phase via a transient
                    // taskSpec field — the runPhase reader picks it up
                    // and clears it after rendering.
                    if (cont.hintForNextPhase() != null) {
                        Map<String, Object> spec = node.getTaskSpec() == null
                                ? new LinkedHashMap<>() : new LinkedHashMap<>(node.getTaskSpec());
                        spec.put("_phaseHint", cont.hintForNextPhase());
                        node.setTaskSpec(spec);
                        nodeService.save(node);
                    }
                    continue;
                }
                if (trans instanceof MarvinNodeStateMachine.CallRecipe cr) {
                    counters = cr.newCounters();
                    persistCounters(node, counters);
                    runCallRecipe(process, ctx, node, cr.call());
                    // After the recipe reply lands in chat history,
                    // the next phase is REFLECT.
                    nodeService.setCurrentPhase(node, WorkerPhase.REFLECT);
                    node.setCurrentPhase(WorkerPhase.REFLECT);
                    continue;
                }
                if (trans instanceof MarvinNodeStateMachine.SpawnChildren sc) {
                    counters = sc.newCounters();
                    persistCounters(node, counters);
                    spawnChildren(process, node, sc.children());
                    // Mark DONE (not WAITING) so the DFS treats this
                    // node as transparent and descends into the freshly
                    // spawned children. The awaitingPostChildren flag
                    // tells the next runTurn's reactivation-sweeper to
                    // resurrect this node with phase=POST_CHILDREN once
                    // all children terminate.
                    node.setAwaitingPostChildren(true);
                    Map<String, Object> art = node.getArtifacts() == null
                            ? new LinkedHashMap<>()
                            : new LinkedHashMap<>(node.getArtifacts());
                    art.put("spawnedChildren", sc.children().size());
                    nodeService.markDone(node, art);
                    // Self-wakeup: spawned children are PENDING and
                    // have no listener that would re-fire the lane on
                    // their own. Without this scheduleTurn the lane
                    // goes IDLE after parking and the freshly spawned
                    // children sit untouched until something external
                    // pokes Marvin (process_steer, ProcessEvent, …).
                    // The previous behaviour appeared to work only
                    // when start()-emitted wakeups happened to still
                    // be queued — a single SpawnChildren in the same
                    // process exposed the gap (Vibecoding sess_dad31d6
                    // 8 — 6 min stall after the second NEEDS_SUBTASKS).
                    // AskUserInput / CallRecipe correctly stay quiet —
                    // they wait for an external trigger, this branch
                    // is the only "parked-but-more-work" case.
                    eventEmitter.scheduleTurn(process.getId());
                    return true;
                }
                if (trans instanceof MarvinNodeStateMachine.AskUserInput aui) {
                    counters = aui.newCounters();
                    persistCounters(node, counters);
                    boolean parked = spawnUserInputSibling(process, ctx, node, aui.spec());
                    return parked;
                }
                if (trans instanceof MarvinNodeStateMachine.FinishDone done) {
                    persistCounters(node, counters);
                    Map<String, Object> artifacts = new LinkedHashMap<>();
                    artifacts.put("result", done.result());
                    if (done.validatorForced()) {
                        artifacts.put("validatorAuditWarning",
                                "validator not satisfied after caps — accepted last candidate");
                    }
                    nodeService.markDone(node, artifacts);
                    // Engine-side draft: persist this node's CONCLUDE
                    // result to _marvin-drafts/<processId>/... for
                    // human inspection. Failure is non-fatal.
                    writeNodeDraft(process, node, done.result());
                    // Engine-side postActions.
                    if (done.postActions() != null && !done.postActions().isEmpty()) {
                        runPostActions(process, node, done.postActions(), artifacts);
                    }
                    log.info("Marvin id='{}' node='{}' DONE ({} chars){}",
                            process.getId(), node.getId(),
                            done.result() == null ? 0 : done.result().length(),
                            done.validatorForced() ? " [forced]" : "");
                    emitNodeDoneStatus(process, node,
                            (done.result() == null ? 0 : done.result().length()) + " chars");
                    return false;
                }
                if (trans instanceof MarvinNodeStateMachine.FinishFailed ff) {
                    persistCounters(node, counters);
                    nodeService.markFailed(node, ff.reason());
                    log.info("Marvin id='{}' node='{}' FAILED — {}",
                            process.getId(), node.getId(), ff.reason());
                    return false;
                }
                // Fall-through guard.
                nodeService.markFailed(node, "unknown transition class: " + trans.getClass());
                return false;
            }
        } catch (RuntimeException e) {
            log.warn("Marvin id='{}' node='{}' phase loop crashed: {}",
                    process.getId(), node.getId(), e.toString(), e);
            nodeService.markFailed(node, "phase loop crashed: " + e.getMessage());
            return false;
        }
    }

    private void persistCounters(
            MarvinNodeDocument node, MarvinNodeStateMachine.Counters c) {
        node.setReflectIter(c.reflectIter());
        node.setValidateIter(c.validateIter());
        node.setConcludeRetries(c.concludeRetries());
        nodeService.save(node);
    }

    private MarvinNodeStateMachine.Caps readCaps(ThinkProcessDocument process) {
        int reflectMax = paramInt(process, "reflectMaxIterations",
                properties.getReflectMaxIterations());
        int validateMax = paramInt(process, "validateMaxIterations",
                properties.getValidateMaxIterations());
        int concludeRetries = paramInt(process, "concludeMaxRetries",
                properties.getConcludeMaxRetries());
        int treeDepth = paramInt(process, "maxTreeDepth",
                properties.getMaxTreeDepth());
        return new MarvinNodeStateMachine.Caps(
                reflectMax, validateMax, concludeRetries, treeDepth);
    }

    /**
     * Executes a single phase: builds the chat messages, sends to
     * the LLM, parses the output, persists a {@link PhaseIteration}
     * record, and asks {@link MarvinNodeStateMachine} for the next
     * transition.
     */
    private MarvinNodeStateMachine.Transition runPhase(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            MarvinNodeDocument node,
            WorkerPhase phase,
            MarvinNodeStateMachine.Caps caps,
            MarvinNodeStateMachine.Counters counters) {
        if (phase == WorkerPhase.VALIDATE
                && counters.validateIter() >= caps.validateMax()) {
            // Hit the validate cap — force DONE on the last candidate.
            String candidate = nullSafe(node.getCandidateResult());
            return MarvinNodeStateMachine.validateCapExhausted(
                    candidate, lastPostActionsFromHistory(node));
        }

        // Read the optional one-shot hint stored by the previous phase.
        @Nullable String hint = paramString(node, "_phaseHint", null);
        clearPhaseHint(node);

        de.mhus.vance.brain.ai.EngineChatFactory.EngineChatBundle bundle =
                engineChatFactory.forProcess(process, ctx, NAME);
        AiChat ai = bundle.chat();
        AiChatConfig config = bundle.primaryConfig();

        String systemPrompt = enginePromptResolver.resolve(
                process, WORKER_SYSTEM_PROMPT_PATH, FALLBACK_WORKER_SYSTEM_PROMPT);
        de.mhus.vance.brain.prompt.PromptContextBuilder sysCtxBuilder =
                de.mhus.vance.brain.prompt.PromptContextBuilder
                        .forProcess(process, null)
                        .tier(de.mhus.vance.brain.ai.ModelSize.LARGE)
                        .engine(NAME)
                        .withRootDirTypes(workspaceService.getRootDirTypes(
                                process.getTenantId(), process.getProjectId()));
        composer.withAddons(NAME, sysCtxBuilder);
        String renderedSystem = composer.render(systemPrompt, sysCtxBuilder.build());

        List<MarvinNodeDocument> allNodes = nodeService.listAll(process.getId());
        String planSnapshot = planSnapshotRenderer.render(allNodes, node.getId());
        int nodeDepth = computeDepth(node, allNodes);

        String userBody = buildPhaseUserMessage(
                process, node, phase, counters, caps, planSnapshot, nodeDepth, hint);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(renderedSystem));
        messages.add(UserMessage.from(userBody));

        String modelAlias = config.provider() + ":" + config.modelName();
        long startMs = System.currentTimeMillis();
        ChatRequest request = ChatRequest.builder().messages(messages).build();
        ChatResponse response = ai.chatModel().chat(request);
        llmCallTracker.record(
                process, request, response, System.currentTimeMillis() - startMs, modelAlias);
        AiMessage reply = response.aiMessage();
        String text = reply == null ? "" : nullSafe(reply.text());

        // Parse the output per phase.
        return parseAndRoute(process, node, phase, text, counters, caps, modelAlias);
    }

    private MarvinNodeStateMachine.Transition parseAndRoute(
            ThinkProcessDocument process,
            MarvinNodeDocument node,
            WorkerPhase phase,
            String llmText,
            MarvinNodeStateMachine.Counters counters,
            MarvinNodeStateMachine.Caps caps,
            String modelAlias) {
        Instant now = Instant.now();
        switch (phase) {
            case SCOPE -> {
                PhaseOutputParser.Result<ScopeOutput> r = phaseParser.parseScope(llmText);
                if (!r.ok()) {
                    return new MarvinNodeStateMachine.FinishFailed(
                            "SCOPE parse failed: " + r.error());
                }
                recordPhaseIteration(node, WorkerPhase.SCOPE, 0, r.output(), modelAlias, now);
                return MarvinNodeStateMachine.afterScope(r.output(), counters, caps);
            }
            case REFLECT -> {
                PhaseOutputParser.Result<ReflectOutput> r = phaseParser.parseReflect(llmText);
                if (!r.ok()) {
                    return new MarvinNodeStateMachine.FinishFailed(
                            "REFLECT parse failed: " + r.error());
                }
                recordPhaseIteration(node, WorkerPhase.REFLECT,
                        counters.reflectIter(), r.output(), modelAlias, now);
                return MarvinNodeStateMachine.afterReflect(r.output(), counters, caps);
            }
            case POST_CHILDREN -> {
                PhaseOutputParser.Result<PostChildrenOutput> r = phaseParser.parsePostChildren(llmText);
                if (!r.ok()) {
                    return new MarvinNodeStateMachine.FinishFailed(
                            "POST_CHILDREN parse failed: " + r.error());
                }
                recordPhaseIteration(node, WorkerPhase.POST_CHILDREN, 0,
                        r.output(), modelAlias, now);
                int depth = nodeService.depthOf(node);
                return MarvinNodeStateMachine.afterPostChildren(
                        r.output(), counters, caps, depth);
            }
            case CONCLUDE -> {
                PhaseOutputParser.Result<ConcludeOutput> r = phaseParser.parseConclude(llmText);
                if (!r.ok()) {
                    return new MarvinNodeStateMachine.FinishFailed(
                            "CONCLUDE parse failed: " + r.error());
                }
                recordPhaseIteration(node, WorkerPhase.CONCLUDE,
                        counters.concludeRetries(), r.output(), modelAlias, now);
                node.setCandidateResult(r.output().result());
                stashPostActions(node, r.output().postActions());
                nodeService.save(node);
                return MarvinNodeStateMachine.afterConclude(r.output(), counters);
            }
            case VALIDATE -> {
                PhaseOutputParser.Result<ValidateOutput> r = phaseParser.parseValidate(llmText);
                if (!r.ok()) {
                    return new MarvinNodeStateMachine.FinishFailed(
                            "VALIDATE parse failed: " + r.error());
                }
                recordPhaseIteration(node, WorkerPhase.VALIDATE,
                        counters.validateIter(), r.output(), modelAlias, now);
                // Always increment validate iter — it's consumed once
                // a verdict is rendered.
                counters = counters.incValidate();
                persistCounters(node, counters);
                String candidate = nullSafe(node.getCandidateResult());
                List<PostActionSpec> pa = lastPostActionsFromHistory(node);
                return MarvinNodeStateMachine.afterValidate(
                        r.output(), counters, caps, candidate, pa);
            }
        }
        return new MarvinNodeStateMachine.FinishFailed(
                "unknown phase: " + phase);
    }

    private void recordPhaseIteration(
            MarvinNodeDocument node,
            WorkerPhase phase,
            int iter,
            Object output,
            String modelAlias,
            Instant timestamp) {
        try {
            String json = objectMapper.writeValueAsString(output);
            nodeService.appendPhaseHistory(
                    node,
                    new PhaseIteration(phase, iter, json, modelAlias, null, null, timestamp));
        } catch (RuntimeException e) {
            log.debug("Marvin phaseHistory serialise failed node='{}' phase={}: {}",
                    node.getId(), phase, e.toString());
        }
    }

    private void clearPhaseHint(MarvinNodeDocument node) {
        if (node.getTaskSpec() == null) return;
        if (!node.getTaskSpec().containsKey("_phaseHint")) return;
        Map<String, Object> spec = new LinkedHashMap<>(node.getTaskSpec());
        spec.remove("_phaseHint");
        node.setTaskSpec(spec);
        nodeService.save(node);
    }

    /** Persists postActions emitted by CONCLUDE so VALIDATE retries
     *  / cap-forced exits can still find them. */
    private void stashPostActions(MarvinNodeDocument node, @Nullable List<PostActionSpec> pa) {
        if (pa == null || pa.isEmpty()) return;
        Map<String, Object> spec = node.getTaskSpec() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(node.getTaskSpec());
        // Store as raw maps so Jackson roundtrip works without custom
        // (de)serialisers.
        List<Map<String, Object>> raw = new ArrayList<>(pa.size());
        for (PostActionSpec p : pa) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tool", p.tool());
            m.put("args", p.args());
            raw.add(m);
        }
        spec.put("_pendingPostActions", raw);
        node.setTaskSpec(spec);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<PostActionSpec> lastPostActionsFromHistory(
            MarvinNodeDocument node) {
        if (node.getTaskSpec() == null) return null;
        Object raw = node.getTaskSpec().get("_pendingPostActions");
        if (!(raw instanceof List<?> list)) return null;
        List<PostActionSpec> out = new ArrayList<>();
        for (Object e : list) {
            if (!(e instanceof Map<?, ?> m)) continue;
            Object tool = m.get("tool");
            Object args = m.get("args");
            if (tool instanceof String t && args instanceof Map<?, ?> argMap) {
                Map<String, Object> cleanArgs = new LinkedHashMap<>();
                for (Map.Entry<?, ?> en : argMap.entrySet()) {
                    cleanArgs.put(String.valueOf(en.getKey()), en.getValue());
                }
                out.add(new PostActionSpec(t, cleanArgs));
            }
        }
        return out.isEmpty() ? null : out;
    }

    // ──────────────────── Phase prompt builder ────────────────────

    private String buildPhaseUserMessage(
            ThinkProcessDocument process,
            MarvinNodeDocument node,
            WorkerPhase phase,
            MarvinNodeStateMachine.Counters counters,
            MarvinNodeStateMachine.Caps caps,
            String planSnapshot,
            int nodeDepth,
            @Nullable String hint) {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(nullSafe(node.getGoal())).append("\n\n");

        List<String> available = readAvailableRecipes(process);
        if (!available.isEmpty()) {
            sb.append("Available recipes for CALL_RECIPE:\n");
            for (String r : available) {
                String desc = describeRecipe(process, r);
                sb.append("  - ").append(r);
                if (desc != null) sb.append(": ").append(desc);
                sb.append('\n');
            }
            sb.append('\n');
        } else {
            sb.append("(no recipes available for CALL_RECIPE — answer directly via PROCEED_TO_CONCLUDE or decompose via NEEDS_SUBTASKS)\n\n");
        }

        sb.append(planSnapshot).append("\n\n");

        // Depth damper: ramps up discouragement of further
        // NEEDS_SUBTASKS based on this node's depth in the tree —
        // not on the total tree size. Total-count would punish later
        // sibling sub-topics unfairly (whoever runs first burns the
        // shared budget). Depth-based pressure is per-branch, so each
        // top-level sub-topic gets equal room to decompose. The hard
        // cap (caps.maxDepth + maxTreeNodes) still fires at the limit;
        // this block is purely the prompt-side bend-the-curve hint.
        String budgetBlock = renderDepthDamperBlock(nodeDepth, caps.maxTreeDepth());
        if (budgetBlock != null) {
            sb.append(budgetBlock).append("\n\n");
        }

        if (phase == WorkerPhase.POST_CHILDREN) {
            String childBlock = renderChildrenResultsBlock(process, node);
            if (!childBlock.isBlank()) {
                sb.append(childBlock).append("\n");
            }
        }
        if (phase == WorkerPhase.VALIDATE) {
            String candidate = nullSafe(node.getCandidateResult());
            sb.append("Candidate result:\n");
            sb.append(candidate).append("\n\n");
        }
        if (hint != null && !hint.isBlank()) {
            sb.append("Hint from previous phase: ").append(hint).append("\n\n");
        }

        sb.append("Phase: ").append(phase.name());
        if (phase == WorkerPhase.REFLECT) {
            sb.append(" (iteration ").append(counters.reflectIter())
                    .append("/").append(caps.reflectMax()).append(")");
        }
        if (phase == WorkerPhase.VALIDATE) {
            sb.append(" (iteration ").append(counters.validateIter())
                    .append("/").append(caps.validateMax()).append(")");
        }
        sb.append('\n');
        sb.append(phaseInstruction(phase));
        return sb.toString();
    }

    /**
     * Computes the depth of {@code node} in the tree by walking up
     * the {@code parentId} chain through {@code allNodes}. Root nodes
     * (no parent) are depth 0; their direct children are depth 1, and
     * so on. Per-branch — sibling sub-trees get evaluated independently.
     *
     * <p>Defensive: if the chain references a parent not in the
     * provided list (shouldn't happen but possible mid-write), the
     * traversal stops and returns the depth reached so far instead of
     * looping or throwing.
     */
    static int computeDepth(MarvinNodeDocument node, List<MarvinNodeDocument> allNodes) {
        if (node == null) return 0;
        Map<String, MarvinNodeDocument> byId = new HashMap<>();
        for (MarvinNodeDocument n : allNodes) {
            if (n.getId() != null) byId.put(n.getId(), n);
        }
        int depth = 0;
        String parentId = node.getParentId();
        // Guard against a corrupted parent-chain by capping at the
        // number of nodes — can't be deeper than the tree is large.
        int safetyCap = allNodes.size() + 1;
        while (parentId != null && depth < safetyCap) {
            MarvinNodeDocument parent = byId.get(parentId);
            if (parent == null) break;
            depth++;
            parentId = parent.getParentId();
        }
        return depth;
    }

    /**
     * Renders the per-branch depth damper — a progressive nudge that
     * discourages further NEEDS_SUBTASKS as a node sits deeper in the
     * tree. Returns {@code null} for shallow nodes where the damper
     * would just be noise.
     *
     * <p>Depth-based instead of total-node-count: total-count would
     * exhaust the budget on whichever sibling sub-topic runs first
     * and force the later siblings into shallow PROCEED_TO_CONCLUDE.
     * Depth pressure is per-branch — each top-level sub-topic gets
     * the same depth budget regardless of how greedy its predecessors
     * were.
     *
     * <p>Thresholds, anchored to {@code maxTreeDepth} (default 5):
     *
     * <ul>
     *   <li>&lt;40% (depth 0–1 of 5) — silent. Top of the branch,
     *       free to decompose.</li>
     *   <li>40–70% (depth 2–3 of 5) — gentle nudge: prefer
     *       CALL_RECIPE or PROCEED_TO_CONCLUDE if the goal is concrete
     *       enough.</li>
     *   <li>70–100% (depth 4 of 5) — strong: stop decomposing,
     *       finish here.</li>
     *   <li>≥100% — at hard cap; engine will reject further
     *       NEEDS_SUBTASKS.</li>
     * </ul>
     */
    static @Nullable String renderDepthDamperBlock(int depth, int maxDepth) {
        if (maxDepth <= 0) return null;
        int pct = (int) Math.round(100.0 * depth / maxDepth);
        if (pct < 40) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Branch depth: ").append(depth).append('/').append(maxDepth)
                .append(" (this node's level in the tree).");
        if (pct < 70) {
            sb.append(" You are mid-branch — sub-tasks here are usually "
                    + "concrete enough to answer via PROCEED_TO_CONCLUDE or "
                    + "delegate via CALL_RECIPE. Use NEEDS_SUBTASKS only "
                    + "when the goal is still composite and the children "
                    + "would do clearly different work.");
        } else if (pct < 100) {
            sb.append(" Near branch depth cap — do NOT spawn another "
                    + "level. Wrap up this branch with PROCEED_TO_CONCLUDE "
                    + "using the material already gathered, or delegate a "
                    + "single focused CALL_RECIPE. NEEDS_SUBTASKS at this "
                    + "depth fragments the answer without adding value.");
        } else {
            sb.append(" At branch depth cap — further NEEDS_SUBTASKS will "
                    + "be rejected by the engine. Finish via "
                    + "PROCEED_TO_CONCLUDE or CALL_RECIPE.");
        }
        return sb.toString();
    }

    private static String phaseInstruction(WorkerPhase phase) {
        return switch (phase) {
            case SCOPE -> """
                    Decide your next action. Check the PLAN first — if your
                    goal is already covered, do not duplicate work.
                    Respond with a single JSON object:
                    {"action":"CALL_RECIPE"|"PROCEED_TO_CONCLUDE"|"NEEDS_SUBTASKS"|"NEEDS_USER_INPUT"|"BLOCKED_BY_PROBLEM",
                     "recipeCall":{"recipe":"<name>","steerContent":"<initial steer>"},
                     "newTasks":[{"goal":"...","taskKind":"WORKER","taskSpec":{}}],
                     "userInput":{"type":"DECISION|FEEDBACK|APPROVAL","title":"...","body":"..."},
                     "problem":"<short>",
                     "reason":"<one-line>"}
                    Only include fields relevant to your chosen action.
                    """;
            case REFLECT -> """
                    The latest CALL_RECIPE result is above. Decide:
                    - PROCEED_TO_CONCLUDE if sufficient material gathered
                    - CALL_RECIPE for another targeted sub-call (capped)
                    - NEEDS_SUBTASKS if the goal must be split (rare)
                    - NEEDS_USER_INPUT if the user must clarify
                    - BLOCKED_BY_PROBLEM if no path forward exists
                    Respond with a single JSON object:
                    {"action":"CALL_RECIPE"|"PROCEED_TO_CONCLUDE"|"NEEDS_SUBTASKS"|"NEEDS_USER_INPUT"|"BLOCKED_BY_PROBLEM",
                     "recipeCall":{"recipe":"<name>","steerContent":"<initial steer>"},
                     "newTasks":[{"goal":"...","taskKind":"WORKER","taskSpec":{}}],
                     "userInput":{"type":"DECISION|FEEDBACK|APPROVAL","title":"...","body":"..."},
                     "problem":"<short>",
                     "reason":"<one-line>"}
                    Only include fields relevant to your chosen action.
                    For CALL_RECIPE the nested `recipeCall` object is REQUIRED — do not
                    flatten `recipe` and `steerContent` onto the top level.
                    """;
            case POST_CHILDREN -> """
                    Your children have finished. Decide:
                    - PROCEED_TO_CONCLUDE to synthesise their results
                    - NEEDS_SUBTASKS for further decomposition (tree-depth-bounded)
                    - BLOCKED_BY_PROBLEM if children failed irrecoverably
                    Respond with JSON: {"action":"...","newTasks":[...],
                                       "problem":"...","reason":"..."}.
                    """;
            case CONCLUDE -> """
                    Synthesise the gathered material into a final Markdown
                    answer that fully addresses the goal. Be concrete; cite
                    facts from the recipe replies / child results.
                    Optionally include engine-side postActions to persist
                    the result deterministically.
                    Respond with JSON:
                    {"result":"<markdown final answer>",
                     "postActions":[{"tool":"doc_create",
                                     "args":{"path":"...","kind":"text","content":"{{ node.result }}"}}],
                     "reason":"<one-line>"}
                    """;
            case VALIDATE -> """
                    Critically evaluate the candidate result above against
                    the goal. Check: completeness, structure, factual
                    coherence, length appropriateness.
                    Respond with JSON:
                    {"verdict":"PASS"|"RETRY_CONCLUDE"|"NEED_MORE_DATA"|"HARD_FAIL",
                     "issues":["concrete problem 1","problem 2"],
                     "hint":"<what to improve on re-run>",
                     "reason":"<one-line>"}
                    """;
        };
    }

    private String renderChildrenResultsBlock(
            ThinkProcessDocument process, MarvinNodeDocument parent) {
        List<MarvinNodeDocument> kids = nodeService.findChildren(
                process.getId(), parent.getId());
        if (kids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Children results:\n");
        int i = 1;
        for (MarvinNodeDocument k : kids) {
            sb.append("\n<<< Child #").append(i).append(" '")
                    .append(abbrev(k.getGoal())).append("' (")
                    .append(k.getStatus()).append(")");
            if (k.getStatus() == NodeStatus.FAILED && k.getFailureReason() != null) {
                sb.append("\n    failureReason: ").append(k.getFailureReason());
            } else {
                Object res = k.getArtifacts() == null ? null
                        : k.getArtifacts().get("result");
                if (res instanceof String s && !s.isBlank()) {
                    sb.append("\n    result: ")
                            .append(truncate(s, 4000));
                }
            }
            sb.append("\n>>>\n");
            i++;
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n[truncated]";
    }

    // ──────────────────── CALL_RECIPE ────────────────────

    /**
     * Synchronously spawns a sub-process with the given recipe,
     * drives it to a reply, captures the assistant text and
     * appends it as a USER message into the marvin-worker's chat
     * history. Specialist recipes run in their native mode — the
     * Marvin phase contract is NOT layered on top of them.
     *
     * <p>Marvin-engine recipes are blocked as CALL_RECIPE targets
     * in v1 to prevent unbounded cross-Marvin nesting.
     */
    private void runCallRecipe(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            MarvinNodeDocument node,
            RecipeCall call) {
        String recipe = call.recipe();
        AppliedRecipe applied;
        try {
            applied = recipeResolver.apply(
                    process.getTenantId(), ctx.projectId(), recipe,
                    process.getConnectionProfile(),
                    /* params */ null);
        } catch (RecipeResolver.UnknownRecipeException ure) {
            appendCallReply(process, node, recipe,
                    "[CALL_RECIPE failed: unknown recipe '" + recipe + "']");
            log.warn("Marvin id='{}' CALL_RECIPE unknown recipe='{}'",
                    process.getId(), recipe);
            return;
        } catch (RecipeResolver.UnknownEngineException uee) {
            appendCallReply(process, node, recipe,
                    "[CALL_RECIPE failed: " + uee.getMessage() + "]");
            log.warn("Marvin id='{}' CALL_RECIPE unknown engine for '{}': {}",
                    process.getId(), recipe, uee.getMessage());
            return;
        } catch (RuntimeException e) {
            appendCallReply(process, node, recipe,
                    "[CALL_RECIPE resolve failed: " + e.getMessage() + "]");
            log.warn("Marvin id='{}' CALL_RECIPE resolve failed: {}",
                    process.getId(), e.toString());
            return;
        }
        // Block Marvin-via-CALL_RECIPE (v1 hard rule).
        if (NAME.equalsIgnoreCase(applied.engine())) {
            appendCallReply(process, node, recipe,
                    "[CALL_RECIPE rejected: recipe '" + recipe
                            + "' uses engine marvin — calling another Marvin "
                            + "via CALL_RECIPE is blocked in v1. Use NEEDS_SUBTASKS "
                            + "to decompose within the current Marvin tree.]");
            log.info("Marvin id='{}' CALL_RECIPE rejected — marvin-on-marvin '{}'",
                    process.getId(), recipe);
            return;
        }
        // Self-recursion block: same recipe as the calling Marvin
        // process's recipe is not useful here.
        String currentRecipe = process.getRecipeName();
        if (currentRecipe != null && currentRecipe.equalsIgnoreCase(applied.name())) {
            appendCallReply(process, node, recipe,
                    "[CALL_RECIPE rejected: cannot call own recipe '" + recipe + "']");
            log.info("Marvin id='{}' CALL_RECIPE self-recursion blocked '{}'",
                    process.getId(), recipe);
            return;
        }

        ThinkProcessDocument child;
        try {
            String childName = "marvin-call-" + node.getId() + "-"
                    + (node.getCalledSubProcessIds() == null
                            ? 0 : node.getCalledSubProcessIds().size());
            ThinkEngine targetEngine = thinkEngineServiceProvider.getObject()
                    .resolve(applied.engine())
                    .orElseThrow(() -> new IllegalStateException(
                            "Recipe '" + applied.name() + "' references unknown engine '"
                                    + applied.engine() + "'"));
            child = thinkProcessService.create(
                    process.getTenantId(),
                    process.getProjectId(),
                    process.getSessionId(),
                    childName,
                    targetEngine.name(),
                    targetEngine.version(),
                    "Marvin CALL_RECIPE sub-process for node " + node.getId(),
                    /* goal */ call.steerContent(),
                    process.getId(),
                    applied.params(),
                    applied.name(),
                    applied.promptOverride(),
                    applied.promptOverrideAppend(),
                    applied.promptMode(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools(),
                    applied.connectionProfile(),
                    applied.defaultActiveSkills(),
                    applied.allowedSkills() == null
                            ? null : java.util.Set.copyOf(applied.allowedSkills()));
            nodeService.addCalledSubProcessId(node, child.getId());
            thinkEngineServiceProvider.getObject().start(child);
            log.info("Marvin id='{}' CALL_RECIPE spawn child='{}' recipe='{}' async={}",
                    process.getId(), child.getId(), applied.name(),
                    targetEngine.asyncSteer());
            try {
                progressEmitter.emitStatus(process,
                        de.mhus.vance.api.progress.StatusTag.DELEGATING,
                        "CALL_RECIPE → " + applied.name());
            } catch (RuntimeException pe) {
                log.debug("Marvin id='{}' DELEGATING progress emit failed: {}",
                        process.getId(), pe.toString());
            }
        } catch (RuntimeException e) {
            appendCallReply(process, node, recipe,
                    "[CALL_RECIPE spawn failed: " + e.getMessage() + "]");
            log.warn("Marvin id='{}' CALL_RECIPE spawn failed: {}",
                    process.getId(), e.toString(), e);
            return;
        }

        // Drive the sub-process. For sync engines (Ford) we send the
        // steer and wait inline; for async engines we'd have to await
        // the DONE event, which we don't currently expose to a sync
        // CALL_RECIPE — those engines are blocked above (marvin) or
        // simply uncommon in practice (Vogon).
        String reply;
        try {
            driveSubProcessOnce(child, process.getId(), call.steerContent());
            reply = readLastAssistantText(
                    process.getTenantId(), process.getSessionId(), child.getId());
            if (reply == null || reply.isBlank()) {
                reply = "[CALL_RECIPE returned no assistant text]";
            }
        } catch (RuntimeException e) {
            reply = "[CALL_RECIPE drive failed: " + e.getMessage() + "]";
            log.warn("Marvin id='{}' CALL_RECIPE drive failed child='{}': {}",
                    process.getId(), child.getId(), e.toString());
        } finally {
            try {
                thinkEngineServiceProvider.getObject().stop(child);
            } catch (RuntimeException e) {
                log.warn("Marvin id='{}' CALL_RECIPE stop failed child='{}': {}",
                        process.getId(), child.getId(), e.toString());
            }
        }
        appendCallReply(process, node, recipe, reply);
    }

    private void driveSubProcessOnce(
            ThinkProcessDocument child, String marvinProcessId, String content) {
        SteerMessage.UserChatInput message = new SteerMessage.UserChatInput(
                java.time.Instant.now(),
                /*idempotencyKey*/ null,
                "marvin:" + marvinProcessId,
                content);
        try {
            laneScheduler.submit(child.getId(),
                    () -> thinkEngineServiceProvider.getObject().steer(child, message)).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AiChatException(
                    "Marvin CALL_RECIPE interrupted child='" + child.getId() + "'", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new AiChatException(
                    "Marvin CALL_RECIPE turn failed child='" + child.getId()
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

    /**
     * Appends the recipe reply as a USER message into the
     * marvin-worker's chat history (i.e. the Marvin process's
     * own chat log). That history is consumed by the next phase
     * call via the {@code ChatMessageService}-fed memory builder
     * — but the current implementation rebuilds the LLM message
     * list inline per call ({@code buildPhaseUserMessage} only
     * passes the latest phase user msg), so we ALSO persist the
     * reply onto the node so the next REFLECT phase can read it.
     */
    private void appendCallReply(
            ThinkProcessDocument process,
            MarvinNodeDocument node,
            String recipe,
            String reply) {
        String marker = "<<< Result of CALL_RECIPE('" + recipe + "'):\n";
        String capped = reply;
        int cap = properties.getRecipeReplyTruncateChars();
        if (capped.length() > cap) {
            capped = capped.substring(0, cap)
                    + "\n[truncated; full reply persisted in sub-process history]";
        }
        String block = marker + capped + "\n>>>";

        // Persist on the node as a structured artifact for later
        // phases to access via the user-message builder.
        Map<String, Object> art = node.getArtifacts() == null
                ? new LinkedHashMap<>() : node.getArtifacts();
        @SuppressWarnings("unchecked")
        List<String> calls = (List<String>) art.get("recipeReplies");
        if (calls == null) {
            calls = new ArrayList<>();
            art.put("recipeReplies", calls);
        }
        calls.add(block);
        node.setArtifacts(art);
        nodeService.save(node);

        // Best-effort: also persist to the Marvin process's chat
        // history so the inspector / Web UI can show the recipe
        // round-trips. Failures don't break the phase loop —
        // the reply is already on the node's artifacts.
        try {
            ChatMessageDocument msg = ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.USER)
                    .content(block)
                    .build();
            chatMessageService.append(msg);
        } catch (RuntimeException e) {
            log.debug("Marvin id='{}' chat-history append for CALL_RECIPE reply failed: {}",
                    process.getId(), e.toString());
        }
    }

    // ──────────────────── NEEDS_SUBTASKS ────────────────────

    /**
     * Appends the proposed children as WORKER nodes under the
     * current node. EXPAND_FROM_DOC is honored when the spec
     * carries the required {@code documentRef}+{@code childTemplate};
     * other TaskKinds are rejected by the spec parser.
     */
    private void spawnChildren(
            ThinkProcessDocument process,
            MarvinNodeDocument parent,
            List<NewTaskSpec> children) {
        if (children == null || children.isEmpty()) return;
        List<NodeSpec> specs = new ArrayList<>(children.size());
        for (NewTaskSpec c : children) {
            TaskKind kind = c.taskKind() == null ? TaskKind.WORKER : c.taskKind();
            Map<String, Object> spec = c.taskSpec() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(c.taskSpec());
            specs.add(new NodeSpec(c.goal(), kind, spec));
        }
        nodeService.appendChildren(
                process.getTenantId(), process.getId(),
                parent.getId() == null ? "" : parent.getId(), specs);
        log.info("Marvin id='{}' node='{}' spawned {} children (NEEDS_SUBTASKS)",
                process.getId(), parent.getId(), specs.size());
    }

    // ──────────────────── NEEDS_USER_INPUT ────────────────────

    /**
     * Inserts a USER_INPUT sibling AFTER the worker node and marks
     * the worker DONE with awaitingUserInputNode pointer. Mirrors
     * v1's pattern so the inbox-answer router still finds the node
     * via the inbox-item id.
     */
    private boolean spawnUserInputSibling(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            MarvinNodeDocument node,
            UserInputSpec spec) {
        if (ctx.userId() == null) {
            nodeService.markFailed(node,
                    "NEEDS_USER_INPUT requires session-owner userId; none resolved");
            return false;
        }
        Map<String, Object> taskSpec = new LinkedHashMap<>();
        taskSpec.put("type", spec.type());
        if (spec.title() != null) taskSpec.put("title", spec.title());
        if (spec.body() != null) taskSpec.put("body", spec.body());
        if (spec.criticality() != null) taskSpec.put("criticality", spec.criticality());
        if (spec.payload() != null && !spec.payload().isEmpty()) {
            taskSpec.put("payload", spec.payload());
        }
        NodeSpec ns = new NodeSpec(
                spec.title() == null || spec.title().isBlank()
                        ? "User input requested by worker" : spec.title(),
                TaskKind.USER_INPUT,
                taskSpec);
        MarvinNodeDocument inputNode = nodeService.insertSiblingAfter(
                process.getTenantId(), node, ns);

        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("awaitingUserInputNode", inputNode.getId());
        nodeService.markDone(node, artifacts);
        log.info("Marvin id='{}' NEEDS_USER_INPUT node='{}' inserted USER_INPUT sibling='{}'",
                process.getId(), node.getId(), inputNode.getId());
        return false;
    }

    // ──────────────────── EXPAND_FROM_DOC ────────────────────

    private void runExpandFromDoc(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            MarvinNodeDocument node) {
        @SuppressWarnings("unchecked")
        Map<String, Object> documentRef = paramMap(node, "documentRef");
        if (documentRef == null || documentRef.isEmpty()) {
            nodeService.markFailed(node,
                    "EXPAND_FROM_DOC missing taskSpec.documentRef");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> childTemplate = paramMap(node, "childTemplate");
        if (childTemplate == null || childTemplate.isEmpty()) {
            nodeService.markFailed(node,
                    "EXPAND_FROM_DOC missing taskSpec.childTemplate");
            return;
        }
        String treeMode = paramString(node, "treeMode", "RECURSIVE");
        boolean failOnEmpty = paramBool(node, "failOnEmpty", false);
        boolean strictMissing = paramBool(node, "failOnMissingField", false);
        DocumentExpander.ExpansionPlan plan;
        try {
            plan = documentExpander.expand(
                    process.getTenantId(), ctx.projectId(),
                    documentRef, childTemplate, treeMode,
                    node.getGoal(), strictMissing);
        } catch (DocumentExpander.ExpandError ee) {
            nodeService.markFailed(node, ee.getMessage());
            return;
        } catch (RuntimeException e) {
            nodeService.markFailed(node, "EXPAND_FROM_DOC failed: " + e.getMessage());
            return;
        }
        if (plan.nodes().isEmpty()) {
            if (failOnEmpty) {
                nodeService.markFailed(node,
                        "EXPAND_FROM_DOC: document yielded 0 items (failOnEmpty=true)");
                return;
            }
            Map<String, Object> artifacts = new LinkedHashMap<>();
            artifacts.put("expanded", true);
            artifacts.put("childCount", 0);
            nodeService.markDone(node, artifacts);
            return;
        }
        int total = appendExpansionPlan(process, node.getId(), plan.nodes());
        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("expanded", true);
        artifacts.put("childCount", total);
        nodeService.markDone(node, artifacts);
        log.info("Marvin id='{}' EXPAND node='{}' materialized {} node(s)",
                process.getId(), node.getId(), total);
    }

    private int appendExpansionPlan(
            ThinkProcessDocument process, String parentId,
            List<DocumentExpander.TemplatedNode> templated) {
        if (templated.isEmpty()) return 0;
        List<NodeSpec> directSpecs = new ArrayList<>(templated.size());
        for (DocumentExpander.TemplatedNode tn : templated) {
            directSpecs.add(tn.spec());
        }
        List<MarvinNodeDocument> directNodes = nodeService.appendChildren(
                process.getTenantId(), process.getId(), parentId, directSpecs);
        int total = directNodes.size();
        for (int i = 0; i < directNodes.size(); i++) {
            DocumentExpander.TemplatedNode tn = templated.get(i);
            if (!tn.children().isEmpty()) {
                total += appendExpansionPlan(process, directNodes.get(i).getId(), tn.children());
            }
        }
        return total;
    }

    // ──────────────────── USER_INPUT ────────────────────

    private boolean runUserInput(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            MarvinNodeDocument node) {
        String targetUserId = ctx.userId();
        if (targetUserId == null) {
            nodeService.markFailed(node,
                    "USER_INPUT requires session-owner userId; none resolved");
            return false;
        }
        InboxItemType type = parseInboxItemType(
                paramString(node, "type", null), InboxItemType.FEEDBACK);
        Criticality crit = parseCriticality(
                paramString(node, "criticality", null), Criticality.NORMAL);
        String title = paramString(node, "title", node.getGoal());
        String body = paramString(node, "body", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = paramMap(node, "payload");
        try {
            InboxItemDocument toCreate = InboxItemDocument.builder()
                    .tenantId(process.getTenantId())
                    .originatorUserId("marvin:" + process.getId())
                    .assignedToUserId(targetUserId)
                    .originProcessId(process.getId())
                    .originSessionId(process.getSessionId())
                    .type(type)
                    .criticality(crit)
                    .tags(List.of("marvin"))
                    .title(title == null ? "Marvin asks" : title)
                    .body(body)
                    .payload(payload == null ? new LinkedHashMap<>() : payload)
                    .requiresAction(true)
                    .build();
            InboxItemDocument saved = inboxItemService.create(toCreate);
            nodeService.setInboxItemId(node, saved.getId());
            nodeService.markWaiting(node);
            log.info("Marvin id='{}' USER_INPUT node='{}' item='{}' type={} crit={}",
                    process.getId(), node.getId(), saved.getId(), type, crit);
            return true;
        } catch (RuntimeException e) {
            nodeService.markFailed(node, "USER_INPUT failed: " + e.getMessage());
            return false;
        }
    }

    // ──────────────────── Draft persistence ────────────────────

    /**
     * Persist a node's CONCLUDE-result as a Markdown draft under
     * {@code _marvin-drafts/<processId>/<position-path>__<slug>.md}.
     * Deterministic, engine-side; no LLM involvement. Drafts are a
     * debug / audit aid — they let a human inspect what each
     * sub-worker produced, including the intermediate synthesis a
     * parent did over its children before its own postActions ran.
     *
     * <p>The file body carries a small YAML-front-matter header
     * (node id, goal, phase counters, timestamp) followed by the
     * raw result Markdown. Failures are logged at WARN and
     * swallowed — drafts must never break the engine.
     */
    private void writeNodeDraft(
            ThinkProcessDocument process,
            MarvinNodeDocument node,
            @Nullable String result) {
        if (result == null || result.isBlank()) return;
        try {
            String positionPath = buildPositionPath(node);
            String slug = slugify(node.getGoal(), 60);
            String filename = positionPath + "__" + slug + ".md";
            String path = "_marvin-drafts/" + process.getId() + "/" + filename;
            String body = renderDraftBody(node, result);
            String title = "Marvin draft — " + abbrev(node.getGoal());
            String tenantId = process.getTenantId();
            String projectId = process.getProjectId();
            var existing = documentService.findByPath(tenantId, projectId, path);
            if (existing.isPresent()) {
                documentService.update(
                        existing.get().getId(), title, /*tags*/ null, body, /*newPath*/ null);
            } else {
                documentService.createText(
                        tenantId, projectId, path, title,
                        List.of("marvin", "draft", "process-" + process.getId()),
                        body,
                        "marvin:" + process.getId());
            }
            log.info("Marvin id='{}' wrote draft node='{}' path='{}' ({} chars)",
                    process.getId(), node.getId(), path, result.length());
        } catch (RuntimeException e) {
            log.warn("Marvin id='{}' draft persistence failed node='{}': {}",
                    process.getId(), node.getId(), e.toString());
        }
    }

    private String buildPositionPath(MarvinNodeDocument node) {
        java.util.Deque<Integer> positions = new java.util.ArrayDeque<>();
        MarvinNodeDocument cur = node;
        int guard = 32; // prevent runaway in cyclic data
        while (cur != null && guard-- > 0) {
            positions.push(cur.getPosition());
            String pid = cur.getParentId();
            if (pid == null) break;
            cur = nodeService.findById(pid).orElse(null);
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Integer p : positions) {
            if (!first) sb.append('-');
            first = false;
            sb.append(p);
        }
        return sb.length() == 0 ? "x" : sb.toString();
    }

    private String renderDraftBody(MarvinNodeDocument node, String result) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("nodeId: ").append(node.getId()).append('\n');
        sb.append("taskKind: ").append(node.getTaskKind()).append('\n');
        sb.append("status: ").append(node.getStatus()).append('\n');
        if (node.getCurrentPhase() != null) {
            sb.append("currentPhase: ").append(node.getCurrentPhase()).append('\n');
        }
        sb.append("reflectIter: ").append(node.getReflectIter()).append('\n');
        sb.append("validateIter: ").append(node.getValidateIter()).append('\n');
        sb.append("concludeRetries: ").append(node.getConcludeRetries()).append('\n');
        if (node.getCompletedAt() != null) {
            sb.append("completedAt: ").append(node.getCompletedAt()).append('\n');
        }
        if (node.getPhaseHistory() != null && !node.getPhaseHistory().isEmpty()) {
            sb.append("phaseHistory:\n");
            for (var ph : node.getPhaseHistory()) {
                sb.append("  - ").append(ph.phase())
                        .append(" iter=").append(ph.iterationIndex())
                        .append(" at ").append(ph.timestamp())
                        .append('\n');
            }
        }
        sb.append("---\n\n");
        sb.append("# ").append(nullSafe(node.getGoal())).append("\n\n");
        sb.append(result);
        return sb.toString();
    }

    /** URL-safe slug: lowercase, non-alnum runs → "-", trim edge
     *  hyphens, truncate to {@code maxLen}. Mirrors the Pebble
     *  {@code | slug} filter for engine-side use. */
    private static String slugify(@Nullable String input, int maxLen) {
        if (input == null) return "untitled";
        String s = input.toLowerCase(java.util.Locale.ROOT);
        s = s.replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("(^-+|-+$)", "");
        if (s.isBlank()) return "untitled";
        if (s.length() > maxLen) s = s.substring(0, maxLen);
        s = s.replaceAll("-+$", "");
        return s.isBlank() ? "untitled" : s;
    }

    // ──────────────────── postActions ────────────────────

    private void runPostActions(
            ThinkProcessDocument process,
            MarvinNodeDocument node,
            List<PostActionSpec> actions,
            Map<String, Object> nodeArtifacts) {
        if (actions == null || actions.isEmpty()) return;
        Map<String, Object> renderContext = buildPostActionContext(process, node, nodeArtifacts);
        for (PostActionSpec a : actions) {
            String tool = a.tool();
            if (tool == null || tool.isBlank()) {
                log.warn("Marvin id='{}' postAction skipped — missing tool", process.getId());
                continue;
            }
            try {
                switch (tool.trim()) {
                    case "doc_create" -> execDocCreate(process, node, a.args(), renderContext);
                    default -> log.warn("Marvin id='{}' postAction tool='{}' unknown — skipping",
                            process.getId(), tool);
                }
            } catch (RuntimeException e) {
                log.warn("Marvin id='{}' postAction tool='{}' node='{}' failed: {}",
                        process.getId(), tool, node.getId(), e.toString());
            }
        }
    }

    private void execDocCreate(
            ThinkProcessDocument process,
            MarvinNodeDocument node,
            Map<String, Object> args,
            Map<String, Object> renderContext) {
        String rawPath = optString(args, "path");
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException(
                    "doc_create postAction requires non-blank args.path");
        }
        Object contentObj = args == null ? null : args.get("content");
        String rawContent = contentObj instanceof String cs ? cs : null;
        if (rawContent == null) {
            throw new IllegalArgumentException(
                    "doc_create postAction requires args.content (string)");
        }
        String path = renderPostActionTemplate(rawPath, renderContext);
        String content = renderPostActionTemplate(rawContent, renderContext);
        String rawTitle = optString(args, "title");
        String title = rawTitle == null
                ? null : renderPostActionTemplate(rawTitle, renderContext);

        String tenantId = process.getTenantId();
        String projectId = process.getProjectId();
        var existing = documentService.findByPath(tenantId, projectId, path);
        if (existing.isPresent()) {
            documentService.update(
                    existing.get().getId(), title, /*tags*/ null, content, /*newPath*/ null);
            log.info("Marvin id='{}' postAction doc_create node='{}' updated path='{}' ({} chars)",
                    process.getId(), node.getId(), path, content.length());
        } else {
            documentService.createText(
                    tenantId, projectId, path, title,
                    List.of("marvin", "post-action"), content,
                    "marvin:" + process.getId());
            log.info("Marvin id='{}' postAction doc_create node='{}' created path='{}' ({} chars)",
                    process.getId(), node.getId(), path, content.length());
        }
    }

    private Map<String, Object> buildPostActionContext(
            ThinkProcessDocument process,
            MarvinNodeDocument node,
            Map<String, Object> nodeArtifacts) {
        Map<String, Object> nodeCtx = new LinkedHashMap<>();
        Object nodeResult = nodeArtifacts.get("result");
        nodeCtx.put("result", nodeResult == null ? "" : nodeResult);
        nodeCtx.put("goal", nullSafe(node.getGoal()));
        nodeCtx.put("summary", nullSafe(
                (String) nodeArtifacts.getOrDefault("summary", "")));
        Map<String, Object> processParams = new LinkedHashMap<>();
        String goalStr = nullSafe(process.getGoal());
        processParams.put("topic", goalStr);
        processParams.put("goal", goalStr);
        processParams.put("input", goalStr);
        processParams.put("query", goalStr);
        Map<String, Object> processCtx = new LinkedHashMap<>();
        processCtx.put("goal", goalStr);
        processCtx.put("id", nullSafe(process.getId()));
        processCtx.put("params", processParams);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("node", nodeCtx);
        root.put("process", processCtx);
        root.put("result", nodeCtx);
        return root;
    }

    private String renderPostActionTemplate(
            String template, Map<String, Object> ctx) {
        try {
            String rendered = composer.render(template, ctx);
            return rendered == null ? "" : rendered;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "postAction template render failed for '" + template
                            + "': " + e.getMessage(), e);
        }
    }

    // ──────────────────── plan snapshot to progress channel ────────────────────

    private void emitPlanSnapshot(ThinkProcessDocument process) {
        if (process.getId() == null) return;
        List<MarvinNodeDocument> all = nodeService.listAll(process.getId());
        if (all.isEmpty()) return;
        Map<String, List<MarvinNodeDocument>> byParent = new LinkedHashMap<>();
        MarvinNodeDocument root = null;
        for (MarvinNodeDocument n : all) {
            if (n.getParentId() == null) {
                root = n;
            } else {
                byParent.computeIfAbsent(n.getParentId(),
                        k -> new ArrayList<>()).add(n);
            }
        }
        if (root == null) return;
        for (List<MarvinNodeDocument> kids : byParent.values()) {
            kids.sort((a, b) -> Integer.compare(a.getPosition(), b.getPosition()));
        }
        de.mhus.vance.api.progress.PlanNode rootPlanNode = toPlanNode(root, byParent);
        progressEmitter.emitPlan(
                process,
                de.mhus.vance.api.progress.PlanPayload.builder()
                        .rootNode(rootPlanNode)
                        .build());
    }

    private de.mhus.vance.api.progress.PlanNode toPlanNode(
            MarvinNodeDocument node,
            Map<String, List<MarvinNodeDocument>> byParent) {
        List<MarvinNodeDocument> kids = byParent.getOrDefault(node.getId(), List.of());
        List<de.mhus.vance.api.progress.PlanNode> childPlans = kids.isEmpty()
                ? null
                : kids.stream().map(k -> toPlanNode(k, byParent)).toList();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("position", node.getPosition());
        if (node.getCurrentPhase() != null) meta.put("phase", node.getCurrentPhase().name());
        if (node.getFailureReason() != null) {
            meta.put("failureReason", node.getFailureReason());
        }
        if (node.getInboxItemId() != null) {
            meta.put("inboxItemId", node.getInboxItemId());
        }
        return de.mhus.vance.api.progress.PlanNode.builder()
                .id(node.getId() == null ? "" : node.getId())
                .kind(node.getTaskKind().name().toLowerCase())
                .title(node.getGoal())
                .status(node.getStatus().name().toLowerCase())
                .children(childPlans)
                .meta(meta)
                .build();
    }

    // ──────────────────── idle / done ────────────────────

    private void finalizeIdle(ThinkProcessDocument process) {
        if (nodeService.isTreeTerminal(process.getId())) {
            log.info("Marvin id='{}' tree terminal — DONE", process.getId());
            thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            return;
        }
        boolean running = nodeService.hasRunningNodes(process.getId());
        boolean waiting = nodeService.hasWaitingNodes(process.getId());
        if (waiting && !running) {
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
            return;
        }
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    private boolean nodeBudgetExceeded(ThinkProcessDocument process) {
        return nodeService.listAll(process.getId()).size() > properties.getMaxTreeNodes();
    }

    private void emitNodeDoneStatus(
            ThinkProcessDocument process, MarvinNodeDocument node, String detail) {
        try {
            progressEmitter.emitStatus(process,
                    de.mhus.vance.api.progress.StatusTag.NODE_DONE,
                    "Node '" + abbrev(node.getGoal()) + "' done — " + detail);
        } catch (RuntimeException pe) {
            log.debug("Marvin id='{}' NODE_DONE progress emit failed: {}",
                    process.getId(), pe.toString());
        }
    }

    // ──────────────────── helpers ────────────────────

    private static @Nullable String optString(@Nullable Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static @Nullable Object processParam(
            ThinkProcessDocument process, String key) {
        Map<String, Object> p = process.getEngineParams();
        return p == null ? null : p.get(key);
    }

    private static int paramInt(
            ThinkProcessDocument process, String key, int fallback) {
        Object v = processParam(process, key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }

    private static @Nullable Object nodeSpecParam(
            MarvinNodeDocument node, String key) {
        Map<String, Object> p = node.getTaskSpec();
        return p == null ? null : p.get(key);
    }

    private static @Nullable String paramString(
            MarvinNodeDocument node, String key, @Nullable String fallback) {
        Object v = nodeSpecParam(node, key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static boolean paramBool(MarvinNodeDocument node, String key, boolean fallback) {
        Object v = nodeSpecParam(node, key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> paramMap(
            MarvinNodeDocument node, String key) {
        Object v = nodeSpecParam(node, key);
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> readAvailableRecipes(ThinkProcessDocument process) {
        Object raw = processParam(process, "availableRecipes");
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private @Nullable String describeRecipe(ThinkProcessDocument process, String name) {
        try {
            var resolved = recipeLoader.load(
                    process.getTenantId(), process.getProjectId(), name);
            if (resolved.isEmpty()) return null;
            String desc = resolved.get().description();
            return desc == null ? null : desc.lines().findFirst().orElse(desc);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static InboxItemType parseInboxItemType(
            @Nullable String raw, InboxItemType fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return InboxItemType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static Criticality parseCriticality(
            @Nullable String raw, Criticality fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Criticality.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String nullSafe(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String abbrev(@Nullable String s) {
        if (s == null) return "";
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }
}
