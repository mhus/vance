package de.mhus.vance.brain.marvin;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.marvin.MarvinWorkerOutput;
import de.mhus.vance.api.marvin.MarvinWorkerOutput.NewTaskSpec;
import de.mhus.vance.api.marvin.MarvinWorkerOutput.UserInputSpec;
import de.mhus.vance.api.marvin.NodeStatus;
import de.mhus.vance.api.marvin.TaskKind;
import de.mhus.vance.api.marvin.WorkerOutcome;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
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
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
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
 * Marvin — the deep-think engine. Brain the size of a planet, and
 * for once it's allowed to use it on the actual problem.
 *
 * <p>Marvin owns a persistent task-tree of {@link MarvinNodeDocument}s.
 * Each {@link #runTurn} drains the process's pending queue, consolidates
 * worker / inbox events into node-status updates, then walks the tree
 * (pre-order DFS) looking for the next actionable node. Synchronous
 * task-kinds (PLAN, AGGREGATE) call the LLM and finish in the same
 * turn; asynchronous kinds (WORKER, USER_INPUT) park the node in
 * RUNNING/WAITING and return — the next turn (triggered by the worker's
 * ProcessEvent or the InboxAnswer) picks up where this one stopped.
 *
 * <p>See {@code specification/marvin-engine.md} for the full design.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarvinEngine implements ThinkEngine {

    public static final String NAME = "marvin";
    public static final String VERSION = "0.1.0";

    /** Engine-default fallback prompt — the bundled {@code marvin}
     *  recipe normally supplies the rich PLAN/AGGREGATE prompts. */
    private static final String ENGINE_FALLBACK_PROMPT =
            "You are Marvin, the Vance deep-think engine. You decompose "
                    + "goals into sub-tasks, delegate concrete work to worker "
                    + "processes, and synthesize their results.";

    /**
     * Document-cascade paths for Marvin's PLAN and AGGREGATE prompts.
     * Tenants/users can override the file at the matching path
     * (project / {@code _vance} / classpath:vance-defaults/) without
     * touching source.
     */
    private static final String PLAN_PROMPT_PATH = "prompts/marvin-plan.md";
    private static final String AGGREGATE_PROMPT_PATH = "prompts/marvin-aggregate.md";

    /** System prompt for the synchronous PLAN-call. The model must
     *  return a JSON document with a {@code children} array; we parse
     *  it into {@link NodeSpec}s and append them under the PLAN node. */
    private static final String PLAN_SYSTEM_PROMPT =
            """
            You decompose a parent-goal into a small list of sequential subtasks.

            Return ONLY a JSON object of this shape (no prose, no Markdown):
            {
              "children": [
                {
                  "goal":     "<one-sentence goal for this subtask>",
                  "taskKind": "PLAN" | "WORKER" | "USER_INPUT" | "AGGREGATE",
                  "taskSpec": { ... task-kind-specific spec ... }
                },
                ...
              ]
            }

            Rules:
            - Order matters; siblings run sequentially.
            - PLAN  - further decomposition (use sparingly).
            - WORKER - taskSpec.recipe + taskSpec.steerContent must be set.
                       Prefer recipe="marvin-worker" — it understands the
                       Marvin worker output contract (DONE / NEEDS_SUBTASKS /
                       NEEDS_USER_INPUT / BLOCKED_BY_PROBLEM) and lets the
                       worker request further decomposition or ask the user
                       on its own. Specialist recipes (web-research,
                       code-read, analyze) work but their output won't carry
                       the structured Marvin contract.
            - USER_INPUT - taskSpec.type (DECISION|FEEDBACK|APPROVAL),
                           taskSpec.title, taskSpec.body, taskSpec.criticality.
            - AGGREGATE - put as the LAST sibling under a parent that has
                          children whose artifacts you want synthesized.
                          taskSpec.prompt is the synthesis instruction.
            - Aim for 2-6 children; never exceed the maxChildren cap.
            """;

    /** System prompt for the synchronous AGGREGATE-call. */
    private static final String AGGREGATE_SYSTEM_PROMPT =
            "You synthesize the artifacts of sibling-nodes (already DONE) into "
                    + "a single coherent summary. Stay below the maxOutputChars "
                    + "limit. Quote concrete data from the siblings rather than "
                    + "vaguely referencing them.";

    /**
     * Postfix appended to every worker's initial steer-message.
     * Belt-and-braces: the {@code marvin-worker} recipe carries the
     * same instruction in its system prompt, but appending it as the
     * last line of the user message keeps the schema right under the
     * model's nose at the end of the prompt where instructions stick
     * best. See {@code specification/marvin-engine.md} §5a.
     */
    private static final String WORKER_SCHEMA_POSTFIX = """

            ---
            STRICT OUTPUT FORMAT — read carefully.
            Your final reply MUST end with a single JSON object of this shape
            (no fences required, no other JSON in the reply):

            {
              "outcome": "DONE" | "NEEDS_SUBTASKS" | "NEEDS_USER_INPUT" | "BLOCKED_BY_PROBLEM",
              "result": "<Markdown — your final answer when outcome=DONE; partial result otherwise>",
              "newTasks": [
                {"goal": "...", "taskKind": "WORKER", "taskSpec": {"recipe": "...", "steerContent": "..."}}
              ],
              "userInput": {"type": "DECISION|FEEDBACK|APPROVAL", "title": "...", "body": "..."},
              "problem": "<short description if BLOCKED_BY_PROBLEM>",
              "reason": "<one-line explanation of your chosen outcome>"
            }

            Only include fields relevant to the outcome.
            If you cannot finish without help, use NEEDS_USER_INPUT or
            BLOCKED_BY_PROBLEM rather than guessing. Do not embed the
            schema as an example — emit the actual filled-in object.
            """;

    /** Hard cap on JSON-format-correction re-prompts per worker. */
    private static final int MAX_OUTPUT_CORRECTIONS = 2;

    /**
     * Marvin's tool cut for PLAN / AGGREGATE turns. Discovery + a
     * narrow set of "look it up before deciding" tools so a PLAN
     * isn't built from training-data guesses about a topic Marvin
     * has never heard of. The actual heavy work still happens in
     * spawned WORKER nodes (Ford recipes); these tools are for
     * orientation only.
     */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "whoami",
            "current_time",
            "recipe_list",
            "recipe_describe",
            "manual_list",
            "manual_read",
            "web_search",
            "web_fetch",
            "execute_javascript");

    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";

    private final MarvinNodeService nodeService;
    private final MarvinProperties properties;
    private final InboxItemService inboxItemService;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final RecipeResolver recipeResolver;
    private final RecipeLoader recipeLoader;
    private final MarvinWorkerOutputParser workerOutputParser;
    private final de.mhus.vance.brain.progress.LlmCallTracker llmCallTracker;
    private final de.mhus.vance.brain.progress.ProgressEmitter progressEmitter;
    private final de.mhus.vance.brain.thinkengine.EnginePromptResolver enginePromptResolver;
    private final de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory;
    private final ObjectMapper objectMapper;
    private final ProcessEventEmitter eventEmitter;
    private final LaneScheduler laneScheduler;
    /** Lazy because {@code ThinkEngineService} wires every engine
     *  bean — we'd otherwise close a cycle. */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Marvin (Deep-Think)";
    }

    @Override
    public String description() {
        return "Methodical deep-think engine. Builds a dynamic task-tree, "
                + "delegates work to Ford-style workers, asks the user via "
                + "the inbox when needed, and synthesizes the results back.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        return ALLOWED_TOOLS;
    }

    @Override
    public boolean asyncSteer() {
        // Marvin's runTurn happens on its own lane, triggered by
        // start() and re-woken by child events. There's no
        // per-steer reply — orchestrators (Arthur) shouldn't block
        // on process_steer waiting for one.
        return true;
    }

    /**
     * Marvin's parent-report carries the synthesized tree result —
     * the parent (Arthur, Vogon, …) gets the actual content of the
     * deep-think run, not a generic "status=done" line. Lookup
     * order:
     * <ol>
     *   <li>Last DONE child of the root with {@code taskKind=AGGREGATE}
     *       — that's the canonical synthesis when the PLAN included
     *       one (recommended pattern).</li>
     *   <li>The root node's own {@code result} artifact — covers
     *       trees whose root was a single WORKER or a PLAN that
     *       didn't append an AGGREGATE.</li>
     *   <li>Concatenated {@code result}s of all DONE root-children
     *       — last-resort dump so the parent sees something rather
     *       than nothing.</li>
     * </ol>
     * Plus a small {@code payload} for downstream orchestrators
     * (Vogon-style state-machines) that want structured access.
     */
    @Override
    public ParentReport summarizeForParent(
            ThinkProcessDocument process, ProcessEventType eventType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType.name());
        payload.put("processId", process.getId());
        Optional<MarvinNodeDocument> rootOpt = nodeService.findRoot(process.getId());
        if (rootOpt.isEmpty()) {
            return new ParentReport(
                    "Marvin process '" + process.getId() + "' has no tree yet.",
                    payload);
        }
        MarvinNodeDocument root = rootOpt.get();
        List<MarvinNodeDocument> rootChildren =
                nodeService.findChildren(process.getId(), root.getId());

        // 1. Look for an AGGREGATE child with a synthesis.
        for (int i = rootChildren.size() - 1; i >= 0; i--) {
            MarvinNodeDocument c = rootChildren.get(i);
            if (c.getTaskKind() == TaskKind.AGGREGATE && c.getStatus() == NodeStatus.DONE
                    && c.getArtifacts() != null) {
                Object summary = c.getArtifacts().get("summary");
                if (summary instanceof String s && !s.isBlank()) {
                    payload.put("aggregateNodeId", c.getId());
                    payload.put("nodeCount",
                            nodeService.listAll(process.getId()).size());
                    return new ParentReport(s, payload);
                }
            }
        }

        // 2. Root's own result (e.g. when root was a single WORKER).
        Map<String, Object> rootArtifacts = root.getArtifacts();
        if (rootArtifacts != null) {
            Object res = rootArtifacts.get("result");
            if (res instanceof String s && !s.isBlank()) {
                payload.put("rootNodeId", root.getId());
                payload.put("nodeCount",
                        nodeService.listAll(process.getId()).size());
                return new ParentReport(s, payload);
            }
        }

        // 3. Concatenate child results as fallback.
        StringBuilder sb = new StringBuilder();
        sb.append("Marvin tree finished (").append(eventType.name().toLowerCase())
                .append(") — combined output of ").append(rootChildren.size())
                .append(" child(ren):\n");
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
        if (included == 0) {
            sb.append("\n(no child produced a textual result)");
        }
        payload.put("includedChildCount", included);
        payload.put("nodeCount", nodeService.listAll(process.getId()).size());
        return new ParentReport(sb.toString(), payload);
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
        TaskKind rootKind = parseTaskKind(
                paramString(process, "rootTaskKind", null), TaskKind.PLAN);
        nodeService.createRoot(process.getTenantId(), process.getId(), goal,
                rootKind, /*taskSpec*/ null);
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        // Schedule the first turn on Marvin's own lane — never run it
        // synchronously here, otherwise we'd block whatever lane invoked
        // `start` (typically the Arthur-side `process_create` flow). The
        // PLAN/AGGREGATE chain can take many seconds per LLM round-trip,
        // and the parent lane needs to return immediately.
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
        // Marvin is fundamentally async — the tree runs on Marvin's
        // own lane, kicked off by start() and re-woken by child
        // ProcessEvents / inbox answers via the persistent pending
        // queue. A synchronous runTurn here would block the caller
        // (typically `process_steer` from an Arthur turn) for the
        // full duration of any in-flight LLM call, defeating the
        // whole async point. Schedule a wakeup instead so the lane
        // re-checks the queue, then return immediately.
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
        // One node per lane-task. Synchronous handlers (PLAN /
        // AGGREGATE) re-schedule another turn at the end; async
        // handlers (WORKER / USER_INPUT) park and rely on the
        // external event (ParentNotificationListener / inbox-answer
        // router) to wake the next turn. Keeping each task short
        // ensures unrelated lane work — like a queued
        // {@code process_steer} no-op — can run between Marvin's
        // LLM round-trips without waiting for the whole tree to
        // finish.
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            runTurnInner(process, ctx);
        } finally {
            // Snapshot the (possibly mutated) task-tree to the
            // user-progress side-channel — runs on every turn end,
            // including the failure path, so the HUD never freezes
            // on a stale plan.
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
            // 1. Consume pending events into node-status updates.
            List<SteerMessage> drained = ctx.drainPending();
            for (SteerMessage msg : drained) {
                consumePending(process, msg);
            }

            // 2. Pick the next actionable node.
            Optional<MarvinNodeDocument> nextOpt =
                    nodeService.findNextActionableNode(process.getId());
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
                // Async wait-point — external event will wake the
                // next turn. Nothing more to do.
                finalizeIdle(process);
                return;
            }
            // Synchronous completion (PLAN / AGGREGATE / sync-fail).
            // Schedule the next turn so the lane processes the next
            // node — but yield this task first so any other queued
            // work (e.g. a parent's process_steer no-op) gets a
            // chance to run between LLM round-trips.
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
     * Folds one drained pending message into a node-status update.
     * The two interesting cases are {@link SteerMessage.ProcessEvent}
     * (worker reported back) and {@link SteerMessage.InboxAnswer}
     * (user answered an ask).
     */
    private void consumePending(ThinkProcessDocument process, SteerMessage msg) {
        switch (msg) {
            case SteerMessage.ProcessEvent pe -> handleProcessEvent(process, pe);
            case SteerMessage.InboxAnswer ia -> handleInboxAnswer(process, ia);
            case SteerMessage.UserChatInput uci ->
                    log.debug("Marvin id='{}' ignoring UserChatInput from='{}' — Marvin doesn't talk directly",
                            process.getId(), uci.fromUser());
            case SteerMessage.ToolResult tr ->
                    log.debug("Marvin id='{}' ignoring ToolResult tool='{}' — Marvin uses synchronous tools",
                            process.getId(), tr.toolName());
            case SteerMessage.ExternalCommand ec ->
                    log.info("Marvin id='{}' external command '{}' — not yet routed in v1",
                            process.getId(), ec.command());
            case SteerMessage.PeerEvent pe ->
                    log.debug("Marvin id='{}' ignoring PeerEvent type='{}' (hub-only)",
                            process.getId(), pe.type());
        }
    }

    // ──────────────────── Plan snapshot ────────────────────

    /**
     * Builds a {@link de.mhus.vance.api.progress.PlanPayload} from the
     * current task-tree and pushes it to the user-progress side-channel.
     * Empty trees (process just spawned, root not yet created) are
     * skipped — there's nothing useful to render.
     */
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
                byParent.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n);
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
        if (node.getFailureReason() != null) {
            meta.put("failureReason", node.getFailureReason());
        }
        if (node.getSpawnedProcessId() != null) {
            meta.put("spawnedProcessId", node.getSpawnedProcessId());
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

    /**
     * Reacts to lifecycle events from a worker we spawned. With the
     * synchronous worker pattern (see {@link #runWorker}) Marvin
     * drives the worker explicitly and consumes its reply inline,
     * so the only events that still matter here are surprises:
     * a FAILED/STOPPED for a node that's somehow still
     * non-terminal (engine crash, manual stop, restart-from-crash
     * recovery). Terminal nodes ignore the event — we routed
     * already.
     */
    private void handleProcessEvent(
            ThinkProcessDocument process, SteerMessage.ProcessEvent event) {
        Optional<MarvinNodeDocument> nodeOpt =
                nodeService.findBySpawnedProcessId(event.sourceProcessId());
        if (nodeOpt.isEmpty()) {
            log.debug("Marvin id='{}' ProcessEvent for unknown spawn='{}' (type={}) — ignoring",
                    process.getId(), event.sourceProcessId(), event.type());
            return;
        }
        MarvinNodeDocument node = nodeOpt.get();
        if (isTerminalStatus(node.getStatus())) {
            log.debug("Marvin id='{}' worker event {} for already-terminal node='{}' ({}) — ignoring",
                    process.getId(), event.type(), node.getId(), node.getStatus());
            return;
        }
        switch (event.type()) {
            case FAILED, STOPPED -> {
                String reason = "worker " + event.type().name().toLowerCase()
                        + (event.humanSummary() == null ? "" : ": " + event.humanSummary());
                nodeService.markFailed(node, reason);
                log.info("Marvin id='{}' worker {} node='{}' worker='{}'",
                        process.getId(), event.type(), node.getId(), event.sourceProcessId());
            }
            case BLOCKED, STARTED, SUMMARY, DONE -> {
                // Synchronous-driven workers shouldn't reach DONE on
                // their own; if they do (foreign trigger), record the
                // mid-flight note but leave the node alone — runWorker
                // already reads the reply inline.
                if (event.humanSummary() != null) {
                    @Nullable Map<String, Object> existing = node.getArtifacts();
                    if (existing == null) existing = new LinkedHashMap<>();
                    existing.merge("workerNotes",
                            event.humanSummary(),
                            (a, b) -> a + "\n---\n" + b);
                    node.setArtifacts(existing);
                    nodeService.save(node);
                }
            }
        }
    }

    private static boolean isTerminalStatus(NodeStatus s) {
        return s == NodeStatus.DONE || s == NodeStatus.FAILED || s == NodeStatus.SKIPPED;
    }

    /**
     * Routes a parsed {@link MarvinWorkerOutput} onto the tree. Each
     * outcome maps to a deterministic node-status transition; see
     * {@code specification/marvin-engine.md} §5a.
     */
    private void routeWorkerOutput(
            ThinkProcessDocument process,
            MarvinNodeDocument node,
            MarvinWorkerOutput output,
            @Nullable String rawReply) {
        Map<String, Object> artifacts = new LinkedHashMap<>();
        if (output.getReason() != null) artifacts.put("reason", output.getReason());
        if (rawReply != null) artifacts.put("workerRawReply", rawReply);

        switch (output.getOutcome()) {
            case DONE -> {
                if (output.getResult() != null) {
                    artifacts.put("result", output.getResult());
                }
                nodeService.markDone(node, artifacts);
                log.info("Marvin id='{}' worker DONE node='{}' result={} chars",
                        process.getId(), node.getId(),
                        output.getResult() == null ? 0 : output.getResult().length());
            }
            case BLOCKED_BY_PROBLEM -> {
                String reason = output.getProblem()
                        + (output.getReason() == null ? "" : " — " + output.getReason());
                nodeService.markFailed(node, reason);
                log.info("Marvin id='{}' worker BLOCKED node='{}': {}",
                        process.getId(), node.getId(), reason);
            }
            case NEEDS_SUBTASKS -> {
                List<NodeSpec> children = toNodeSpecs(output.getNewTasks());
                if (children.isEmpty()) {
                    nodeService.markFailed(node,
                            "NEEDS_SUBTASKS but newTasks is empty after parsing");
                    log.warn("Marvin id='{}' worker NEEDS_SUBTASKS node='{}' — empty newTasks",
                            process.getId(), node.getId());
                    return;
                }
                nodeService.appendChildren(
                        process.getTenantId(), process.getId(), node.getId(), children);
                if (output.getResult() != null) {
                    artifacts.put("partialResult", output.getResult());
                }
                artifacts.put("expanded", true);
                artifacts.put("childCount", children.size());
                // Mark DONE so the DFS descends into the new children
                // on the next turn. v1: no auto-synthesis at this
                // node — the parent's AGGREGATE (if any) sees the
                // partialResult plus the children's outputs as it
                // walks its own siblings.
                nodeService.markDone(node, artifacts);
                log.info("Marvin id='{}' worker NEEDS_SUBTASKS node='{}' added {} children",
                        process.getId(), node.getId(), children.size());
            }
            case NEEDS_USER_INPUT -> {
                UserInputSpec spec = output.getUserInput();
                if (spec == null) {
                    nodeService.markFailed(node,
                            "NEEDS_USER_INPUT but userInput object missing");
                    return;
                }
                MarvinNodeDocument inputNode = appendUserInputSibling(process, node, spec);
                if (output.getResult() != null) {
                    artifacts.put("partialResult", output.getResult());
                }
                artifacts.put("awaitingUserInputNode", inputNode.getId());
                nodeService.markDone(node, artifacts);
                log.info("Marvin id='{}' worker NEEDS_USER_INPUT node='{}' inserted USER_INPUT sibling='{}'",
                        process.getId(), node.getId(), inputNode.getId());
            }
        }
    }

    /**
     * Reads the latest assistant message for the worker process —
     * that's where the structured JSON lives. Falls back to the
     * full last entry if no ASSISTANT-role row is found.
     */
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

    private static List<NodeSpec> toNodeSpecs(List<NewTaskSpec> newTasks) {
        List<NodeSpec> out = new ArrayList<>(newTasks.size());
        for (NewTaskSpec t : newTasks) {
            if (t == null || t.getGoal() == null || t.getGoal().isBlank()) continue;
            out.add(new NodeSpec(
                    t.getGoal(),
                    t.getTaskKind() == null ? TaskKind.WORKER : t.getTaskKind(),
                    t.getTaskSpec() == null
                            ? new LinkedHashMap<>()
                            : new LinkedHashMap<>(t.getTaskSpec())));
        }
        return out;
    }

    /**
     * Inserts a synthetic USER_INPUT node directly after {@code afterNode}
     * (same parent, position+1). The DFS will pick it up on the next
     * turn and route it through the inbox.
     */
    private MarvinNodeDocument appendUserInputSibling(
            ThinkProcessDocument process,
            MarvinNodeDocument afterNode,
            UserInputSpec spec) {
        Map<String, Object> taskSpec = new LinkedHashMap<>();
        taskSpec.put("type", spec.getType());
        if (spec.getTitle() != null) taskSpec.put("title", spec.getTitle());
        if (spec.getBody() != null) taskSpec.put("body", spec.getBody());
        if (spec.getCriticality() != null) taskSpec.put("criticality", spec.getCriticality());
        if (spec.getPayload() != null && !spec.getPayload().isEmpty()) {
            taskSpec.put("payload", spec.getPayload());
        }
        NodeSpec ns = new NodeSpec(
                spec.getTitle() == null || spec.getTitle().isBlank()
                        ? "User input requested by worker" : spec.getTitle(),
                TaskKind.USER_INPUT,
                taskSpec);
        return nodeService.insertSiblingAfter(process.getTenantId(), afterNode, ns);
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

    /**
     * Drives one node's task-kind handler.
     *
     * @return {@code true} if the node parked (RUNNING/WAITING) and
     *         the turn must yield; {@code false} if the node finished
     *         synchronously and the turn can pick the next.
     */
    private boolean executeNode(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            MarvinNodeDocument node) {
        nodeService.markRunning(node);
        log.info("Marvin id='{}' executing node='{}' kind={} pos={} goal='{}'",
                process.getId(), node.getId(), node.getTaskKind(),
                node.getPosition(), abbrev(node.getGoal()));
        return switch (node.getTaskKind()) {
            case PLAN -> { runPlan(process, ctx, node); yield false; }
            case AGGREGATE -> { runAggregate(process, ctx, node); yield false; }
            case WORKER -> runWorker(process, ctx, node);
            case USER_INPUT -> runUserInput(process, ctx, node);
        };
    }

    // ──────────────────── PLAN ────────────────────

    private void runPlan(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            MarvinNodeDocument node) {
        try {
            de.mhus.vance.brain.ai.EngineChatFactory.EngineChatBundle planBundle =
                    engineChatFactory.forProcess(process, ctx, NAME);
            AiChat ai = planBundle.chat();
            AiChatConfig config = planBundle.primaryConfig();

            int maxChildren = paramInt(node, "maxChildren", properties.getPlanMaxChildren());
            String customPrompt = paramString(node, "prompt", null);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(enginePromptResolver.resolveTiered(
                    process,
                    PLAN_PROMPT_PATH,
                    /*smallOverride*/ null,
                    de.mhus.vance.brain.ai.ModelSize.LARGE,
                    PLAN_SYSTEM_PROMPT)));
            String body = "ROOT goal of the Marvin process:\n"
                    + nullSafe(process.getGoal()) + "\n\n"
                    + "PARENT goal of the node you are decomposing:\n"
                    + node.getGoal() + "\n\n"
                    + "maxChildren: " + maxChildren + "\n\n"
                    + buildRecipeCatalog(process)
                    + (customPrompt == null ? "" : "\n\nAdditional instruction:\n" + customPrompt);
            messages.add(UserMessage.from(body));

            String modelAlias = config.provider() + ":" + config.modelName();
            long startMs = System.currentTimeMillis();
            ChatResponse response = ai.chatModel().chat(
                    ChatRequest.builder().messages(messages).build());
            llmCallTracker.record(
                    process, response, System.currentTimeMillis() - startMs, modelAlias);
            AiMessage reply = response.aiMessage();
            String text = reply == null ? "" : reply.text();
            if (text == null) text = "";

            List<NodeSpec> children = parsePlanChildren(text, maxChildren);
            if (children.isEmpty()) {
                nodeService.markFailed(node,
                        "PLAN produced 0 children — model output: "
                                + abbrev(text));
                return;
            }
            nodeService.appendChildren(
                    process.getTenantId(), process.getId(), node.getId(), children);
            Map<String, Object> artifacts = new LinkedHashMap<>();
            artifacts.put("planRaw", text);
            artifacts.put("childrenAdded", children.size());
            nodeService.markDone(node, artifacts);
            log.info("Marvin id='{}' PLAN node='{}' produced {} children",
                    process.getId(), node.getId(), children.size());
        } catch (RuntimeException e) {
            nodeService.markFailed(node, "PLAN failed: " + e.getMessage());
            log.warn("Marvin id='{}' PLAN node='{}' failed: {}",
                    process.getId(), node.getId(), e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private List<NodeSpec> parsePlanChildren(String raw, int maxChildren) {
        String json = extractJsonObject(raw);
        if (json == null) {
            log.warn("Marvin PLAN: no JSON object found in model output: {}", abbrev(raw));
            return List.of();
        }
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(json, Map.class);
        } catch (RuntimeException e) {
            log.warn("Marvin PLAN: invalid JSON ({}) raw='{}'",
                    e.getMessage(), abbrev(raw));
            return List.of();
        }
        Object childrenRaw = root.get("children");
        if (!(childrenRaw instanceof List<?> list)) {
            log.warn("Marvin PLAN: missing 'children' array; raw='{}'", abbrev(raw));
            return List.of();
        }
        List<NodeSpec> out = new ArrayList<>();
        for (int i = 0; i < list.size() && out.size() < maxChildren; i++) {
            Object entry = list.get(i);
            if (!(entry instanceof Map<?, ?> spec)) continue;
            Object goalRaw = spec.get("goal");
            Object kindRaw = spec.get("taskKind");
            if (!(goalRaw instanceof String goalStr) || goalStr.isBlank()) continue;
            if (!(kindRaw instanceof String kindStr) || kindStr.isBlank()) continue;
            TaskKind kind;
            try {
                kind = TaskKind.valueOf(kindStr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Marvin PLAN: skipping child with unknown taskKind '{}'", kindStr);
                continue;
            }
            Map<String, Object> taskSpec = new LinkedHashMap<>();
            Object specRaw = spec.get("taskSpec");
            if (specRaw instanceof Map<?, ?> ms) {
                for (Map.Entry<?, ?> e : ms.entrySet()) {
                    taskSpec.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            out.add(new NodeSpec(goalStr, kind, taskSpec));
        }
        return out;
    }

    /**
     * Lists the recipes available to the planner so it picks real
     * names instead of inventing {@code RESEARCH_AND_SUMMARIZE}-style
     * fictions. Reads through the project cascade so tenant- and
     * project-overrides are visible alongside bundled defaults.
     */
    private String buildRecipeCatalog(ThinkProcessDocument process) {
        // ThinkProcessDocument has no projectId — RecipeLoader falls
        // back to the _vance system project (tenant-wide + bundled).
        // Project-scoped recipes are still discoverable through the
        // recipe_list tool at runtime.
        java.util.List<ResolvedRecipe> recipes = recipeLoader.listAll(
                process.getTenantId(), null);
        if (recipes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Available WORKER recipes (use one of these for "
                + "every WORKER child's `taskSpec.recipe`; never invent "
                + "recipe names):\n");
        for (ResolvedRecipe r : recipes) {
            // Skip Marvin / Arthur themselves — they aren't sensible
            // workers for a Marvin-spawned child.
            if (r.name().equals(NAME) || r.name().equals("arthur")) continue;
            sb.append("- `").append(r.name()).append("` — ");
            String desc = r.description();
            sb.append(desc == null ? "" : desc.trim().replace('\n', ' '));
            sb.append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    /** Strips any wrapping markdown / prose around the JSON object. */
    private static @Nullable String extractJsonObject(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return raw.substring(start, end + 1);
    }

    // ──────────────────── WORKER ────────────────────

    /**
     * Synchronous worker pattern: spawn the worker, drive one turn,
     * read the structured reply, parse, route, stop the worker.
     * Marvin's lane is held for the worker's full duration — that's
     * intentional, because v1 sequential-children require it and
     * because the worker engines (Ford) don't reach a terminal
     * status on their own (they go RUNNING → READY after each turn,
     * which {@code ParentNotificationListener} silences). Driving
     * the worker explicitly removes the dependency on a DONE event.
     *
     * <p>Always returns {@code false} (synchronously completed) so
     * {@code runTurn} schedules the next iteration on Marvin's lane.
     * The node is marked DONE/FAILED before this method returns.
     */
    private boolean runWorker(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            MarvinNodeDocument node) {
        String recipeName = paramString(node, "recipe", null);
        if (recipeName == null) {
            nodeService.markFailed(node, "WORKER node missing taskSpec.recipe");
            log.warn("Marvin id='{}' WORKER node='{}' missing recipe — failing",
                    process.getId(), node.getId());
            return false;
        }
        String steerContent = paramString(node, "steerContent", node.getGoal());
        Map<String, Object> recipeParams = paramMap(node, "params");

        ThinkProcessDocument child;
        try {
            AppliedRecipe applied = recipeResolver.apply(
                    process.getTenantId(), ctx.projectId(), recipeName,
                    process.getConnectionProfile(), recipeParams);
            ThinkEngine targetEngine = thinkEngineServiceProvider.getObject()
                    .resolve(applied.engine())
                    .orElseThrow(() -> new IllegalStateException(
                            "Recipe '" + applied.name() + "' references unknown engine '"
                                    + applied.engine() + "'"));
            String childName = "marvin-" + node.getId();
            child = thinkProcessService.create(
                    process.getTenantId(),
                    process.getProjectId(),
                    process.getSessionId(),
                    childName,
                    targetEngine.name(),
                    targetEngine.version(),
                    "Marvin worker for node " + node.getId(),
                    node.getGoal(),
                    process.getId(),
                    applied.params(),
                    applied.name(),
                    applied.promptOverride(),
                    applied.promptOverrideSmall(),
                    applied.promptMode(),
                    applied.intentCorrection(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools(),
                    applied.connectionProfile(),
                    applied.defaultActiveSkills(),
                    applied.allowedSkills() == null
                            ? null : java.util.Set.copyOf(applied.allowedSkills()));
            nodeService.setSpawnedProcessId(node, child.getId());
            thinkEngineServiceProvider.getObject().start(child);
            log.info("Marvin id='{}' WORKER node='{}' spawned child='{}' recipe='{}'",
                    process.getId(), node.getId(), child.getId(), applied.name());
        } catch (RecipeResolver.UnknownRecipeException ure) {
            nodeService.markFailed(node, "Unknown recipe: " + recipeName);
            log.warn("Marvin id='{}' WORKER node='{}' unknown recipe '{}' — failing",
                    process.getId(), node.getId(), recipeName);
            return false;
        } catch (RecipeResolver.UnknownEngineException uee) {
            nodeService.markFailed(node, uee.getMessage());
            log.warn("Marvin id='{}' WORKER node='{}' unknown engine — {}",
                    process.getId(), node.getId(), uee.getMessage());
            return false;
        } catch (RuntimeException e) {
            nodeService.markFailed(node, "WORKER spawn failed: " + e.getMessage());
            log.warn("Marvin id='{}' WORKER node='{}' spawn failed: {}",
                    process.getId(), node.getId(), e.toString());
            return false;
        }

        // Drive the worker — including up to MAX_OUTPUT_CORRECTIONS
        // schema-correction round-trips — and route the final output.
        try {
            String steer = steerContent + WORKER_SCHEMA_POSTFIX;
            String workerReply = null;
            MarvinWorkerOutputParser.Result parsed = null;
            for (int attempt = 0; attempt <= MAX_OUTPUT_CORRECTIONS; attempt++) {
                driveWorkerTurn(child, process.getId(), steer);
                workerReply = readLastAssistantText(
                        process.getTenantId(), process.getSessionId(), child.getId());
                parsed = workerOutputParser.parse(workerReply);
                if (parsed.ok()) break;
                if (attempt == MAX_OUTPUT_CORRECTIONS) {
                    String reason = "worker output schema invalid after "
                            + attempt + " correction(s): " + parsed.error();
                    nodeService.markFailed(node, reason);
                    log.warn("Marvin id='{}' WORKER node='{}' giving up — {}",
                            process.getId(), node.getId(), reason);
                    return false;
                }
                log.info("Marvin id='{}' WORKER node='{}' schema-correction {}/{}: {}",
                        process.getId(), node.getId(), attempt + 1,
                        MAX_OUTPUT_CORRECTIONS, parsed.error());
                steer = "VALIDATION CHECK (correction " + (attempt + 1)
                        + "/" + MAX_OUTPUT_CORRECTIONS + "): "
                        + parsed.error()
                        + "\n\nRe-emit your reply ending with the correct JSON object."
                        + WORKER_SCHEMA_POSTFIX;
            }
            routeWorkerOutput(process, node, parsed.output(), workerReply);
        } finally {
            // One-shot worker: stop it now, regardless of outcome,
            // so it doesn't linger as a READY process consuming a
            // session slot. The STOPPED-event will arrive in
            // Marvin's pending queue and be ignored (node is now
            // terminal).
            try {
                thinkEngineServiceProvider.getObject().stop(child);
            } catch (RuntimeException e) {
                log.warn("Marvin id='{}' worker stop failed for child='{}': {}",
                        process.getId(), child.getId(), e.toString());
            }
        }
        return false;
    }

    /**
     * Sends one user-input message to the worker and waits for its
     * lane-turn to complete. Errors are converted to AiChatException
     * so {@link #runWorker} can react / mark the node failed.
     */
    private void driveWorkerTurn(
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
                    "Marvin worker interrupted child='" + child.getId() + "'", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new AiChatException(
                    "Marvin worker turn failed child='" + child.getId()
                            + "': " + cause.getMessage(), cause);
        }
    }

    // ──────────────────── USER_INPUT ────────────────────

    /**
     * Creates an inbox item for a USER_INPUT node and parks it in
     * WAITING. Returns {@code false} only when the item couldn't be
     * created at all — in that case the node is FAILED and runTurn
     * should advance to the next sibling.
     */
    private boolean runUserInput(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            MarvinNodeDocument node) {
        String targetUserId = ctx.userId();
        if (targetUserId == null) {
            nodeService.markFailed(node,
                    "USER_INPUT requires session-owner userId; none resolved");
            log.warn("Marvin id='{}' USER_INPUT node='{}' — no session userId, failing",
                    process.getId(), node.getId());
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
        @SuppressWarnings("unchecked")
        List<Object> tagsRaw = (List<Object>) (Object) paramList(node, "tags");
        List<String> tags = new ArrayList<>();
        if (tagsRaw != null) {
            for (Object o : tagsRaw) {
                if (o instanceof String s && !s.isBlank()) tags.add(s);
            }
        }

        try {
            InboxItemDocument toCreate = InboxItemDocument.builder()
                    .tenantId(process.getTenantId())
                    .originatorUserId("marvin:" + process.getId())
                    .assignedToUserId(targetUserId)
                    .originProcessId(process.getId())
                    .originSessionId(process.getSessionId())
                    .type(type)
                    .criticality(crit)
                    .tags(tags)
                    .title(title == null ? "Marvin asks" : title)
                    .body(body)
                    .payload(payload == null ? new LinkedHashMap<>() : payload)
                    .requiresAction(true)
                    .build();
            InboxItemDocument saved = inboxItemService.create(toCreate);
            nodeService.setInboxItemId(node, saved.getId());
            // If the inbox auto-answered (LOW + default), the steer-router
            // already pushed the answer back; just park as WAITING and let
            // the next turn drain it from the pending queue.
            nodeService.markWaiting(node);
            log.info("Marvin id='{}' USER_INPUT node='{}' item='{}' type={} crit={}",
                    process.getId(), node.getId(), saved.getId(), type, crit);
            return true;
        } catch (RuntimeException e) {
            nodeService.markFailed(node, "USER_INPUT failed: " + e.getMessage());
            log.warn("Marvin id='{}' USER_INPUT node='{}' failed: {}",
                    process.getId(), node.getId(), e.toString());
            return false;
        }
    }

    // ──────────────────── AGGREGATE ────────────────────

    private void runAggregate(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            MarvinNodeDocument node) {
        try {
            // Aggregate the artifacts of this node's siblings (nodes
            // with the same parentId, smaller position) — the spec'ed
            // pattern is "AGGREGATE is the LAST sibling under a PLAN
            // parent". v1: collect every prior sibling regardless of
            // status, drop SKIPPED, surface FAILED reasons too.
            List<MarvinNodeDocument> siblings = nodeService.findChildren(
                    process.getId(), node.getParentId());
            List<MarvinNodeDocument> priors = new ArrayList<>();
            for (MarvinNodeDocument s : siblings) {
                if (s.getId() != null && s.getId().equals(node.getId())) continue;
                if (s.getPosition() >= node.getPosition()) continue;
                if (s.getStatus() == NodeStatus.SKIPPED) continue;
                priors.add(s);
            }
            int maxChars = paramInt(node, "maxOutputChars",
                    properties.getAggregateMaxOutputChars());
            String customPrompt = paramString(node, "prompt", null);

            de.mhus.vance.brain.ai.EngineChatFactory.EngineChatBundle aggregateBundle =
                    engineChatFactory.forProcess(process, ctx, NAME);
            AiChat ai = aggregateBundle.chat();
            AiChatConfig config = aggregateBundle.primaryConfig();

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(enginePromptResolver.resolveTiered(
                    process,
                    AGGREGATE_PROMPT_PATH,
                    /*smallOverride*/ null,
                    de.mhus.vance.brain.ai.ModelSize.LARGE,
                    AGGREGATE_SYSTEM_PROMPT)));
            StringBuilder body = new StringBuilder();
            body.append("Goal of this AGGREGATE node:\n").append(node.getGoal()).append("\n\n");
            if (customPrompt != null) {
                body.append("Synthesis instruction:\n").append(customPrompt).append("\n\n");
            }
            body.append("maxOutputChars: ").append(maxChars).append("\n\n");
            body.append("Sibling artifacts:\n");
            for (MarvinNodeDocument s : priors) {
                body.append("\n--- node ").append(s.getId())
                        .append(" status=").append(s.getStatus())
                        .append(" goal=\"").append(abbrev(s.getGoal())).append("\" ---\n");
                if (s.getStatus() == NodeStatus.FAILED) {
                    body.append("FAILED reason: ").append(s.getFailureReason()).append("\n");
                } else {
                    body.append(renderArtifacts(s.getArtifacts())).append("\n");
                }
            }

            messages.add(UserMessage.from(body.toString()));
            String modelAlias = config.provider() + ":" + config.modelName();
            long startMs = System.currentTimeMillis();
            ChatResponse response = ai.chatModel().chat(
                    ChatRequest.builder().messages(messages).build());
            llmCallTracker.record(
                    process, response, System.currentTimeMillis() - startMs, modelAlias);
            String summary = response.aiMessage() == null
                    ? "" : nullSafe(response.aiMessage().text()).trim();
            if (summary.length() > maxChars) {
                summary = summary.substring(0, maxChars);
            }

            Map<String, Object> artifacts = new LinkedHashMap<>();
            artifacts.put("summary", summary);
            artifacts.put("aggregatedNodeIds",
                    priors.stream().map(MarvinNodeDocument::getId).toList());
            nodeService.markDone(node, artifacts);
            log.info("Marvin id='{}' AGGREGATE node='{}' summarized {} siblings ({} chars)",
                    process.getId(), node.getId(), priors.size(), summary.length());
        } catch (RuntimeException e) {
            nodeService.markFailed(node, "AGGREGATE failed: " + e.getMessage());
            log.warn("Marvin id='{}' AGGREGATE node='{}' failed: {}",
                    process.getId(), node.getId(), e.toString());
        }
    }

    private static String renderArtifacts(@Nullable Map<String, Object> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) return "(no artifacts)";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : artifacts.entrySet()) {
            sb.append(e.getKey()).append(": ");
            String val = String.valueOf(e.getValue());
            if (val.length() > 1500) val = val.substring(0, 1500) + "...";
            sb.append(val).append('\n');
        }
        return sb.toString();
    }

    // ──────────────────── Idle / done ────────────────────

    /**
     * Decides the process status when {@code runTurn} has nothing
     * synchronous left to do. See spec §8 (status aggregation):
     * <ul>
     *   <li>All nodes terminal → {@code DONE}.</li>
     *   <li>At least one node {@code WAITING} on user input
     *       AND no node still {@code RUNNING} → {@code BLOCKED}.
     *       Important: a tree with both RUNNING and WAITING is
     *       <em>not</em> BLOCKED — workers are still going. Going
     *       BLOCKED there would falsely wake the parent (Arthur).</li>
     *   <li>Otherwise → {@code READY} (waiting for child events).</li>
     * </ul>
     */
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

    // ──────────────────── Config resolve (mirrors Ford) ────────────────────

    private static AiChatConfig resolveAiConfig(
            ThinkProcessDocument process,
            SettingService settings,
            AiModelResolver modelResolver) {
        String tenantId = process.getTenantId();
        String paramModel = paramString(process, "model", null);
        String paramProvider = paramString(process, "provider", null);
        String spec;
        if (paramModel != null && paramModel.contains(":")) {
            spec = paramModel;
        } else if (paramModel != null && paramProvider != null) {
            spec = paramProvider + ":" + paramModel;
        } else if (paramModel != null) {
            spec = "default:" + paramModel;
        } else {
            spec = null;
        }
        AiModelResolver.Resolved resolved = modelResolver.resolveOrDefault(
                spec, tenantId, process.getProjectId(), process.getId());
        String apiKeySetting = String.format(SETTING_PROVIDER_API_KEY_FMT, resolved.provider());
        String apiKey = settings.getDecryptedPasswordCascade(
                tenantId, process.getProjectId(), process.getId(), apiKeySetting);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key configured for provider '" + resolved.provider()
                            + "' (tenant='" + tenantId
                            + "', setting='" + apiKeySetting + "')");
        }
        return new AiChatConfig(resolved.provider(), resolved.modelName(), apiKey);
    }

    // ──────────────────── engineParams + node-spec helpers ────────────────────

    private static @Nullable Object processParam(
            ThinkProcessDocument process, String key) {
        Map<String, Object> p = process.getEngineParams();
        return p == null ? null : p.get(key);
    }

    private static @Nullable String paramString(
            ThinkProcessDocument process, String key, @Nullable String fallback) {
        Object v = processParam(process, key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
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

    private static int paramInt(MarvinNodeDocument node, String key, int fallback) {
        Object v = nodeSpecParam(node, key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return fallback; }
        }
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

    private static @Nullable List<?> paramList(MarvinNodeDocument node, String key) {
        Object v = nodeSpecParam(node, key);
        return v instanceof List<?> list ? list : null;
    }

    private static TaskKind parseTaskKind(@Nullable String raw, TaskKind fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return TaskKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
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
