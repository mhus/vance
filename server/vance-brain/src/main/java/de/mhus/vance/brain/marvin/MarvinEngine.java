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
                  "taskKind": "PLAN" | "EXPAND_FROM_DOC" | "WORKER" | "USER_INPUT" | "AGGREGATE",
                  "taskSpec": { ... task-kind-specific spec ... }
                },
                ...
              ]
            }

            Rules:
            - Order matters; siblings run sequentially.
            - PLAN  - further decomposition (use sparingly).
            - EXPAND_FROM_DOC - deterministic decomposition driven by an
                       existing kind:list / kind:tree / kind:records document.
                       Use this when the plan ALREADY EXISTS as a document
                       (chapter outline, requirements list, …) — it iterates
                       items and creates one child per item via a template,
                       no LLM call.
                       taskSpec.documentRef = {"path": "<exact-path>"} OR
                                              {"id":   "<mongo-id>"}.
                       The path/id MUST be one that already exists or that
                       a prior sibling will write — copy it verbatim from
                       the recipe / parent-goal context. NEVER fabricate
                       paths like "tasks/<x>/output.md", "outline_document",
                       "previous_step_result.md", etc. — those are
                       hallucinations and lookup will fail. If the recipe
                       tells you the outline lives at "essay/outline.md",
                       use exactly that string.
                       taskSpec.childTemplate = {goal, taskKind, taskSpec}
                       with {{item.text}} / {{record.<field>}} / {{index}}
                       / {{root.title}} / {{parent.goal}} placeholders.
                       Optional: treeMode (RECURSIVE|FLAT), failOnEmpty,
                       failOnMissingField.
            - WORKER - taskSpec.steerContent must be set; taskSpec.recipe
                       is OPTIONAL.
                       * With recipe set (e.g. "marvin-worker", "web-research",
                         "code-read", "analyze") the engine spawns that recipe
                         directly. Prefer recipe="marvin-worker" when you want
                         the structured Marvin worker output contract (DONE /
                         NEEDS_SUBTASKS / NEEDS_USER_INPUT / BLOCKED_BY_PROBLEM);
                         specialist recipes work but won't carry that contract.
                       * Without recipe — only the goal — the engine routes
                         through process_create_delegate's selector at
                         spawn-time: a one-shot LLM picks the matching project
                         recipe based on engine catalog + recipe inventory, or
                         falls back to Slartibartfast for a freshly-generated
                         recipe (adds 60-180s) when nothing fits. Use this when
                         you have a clear task description but don't want to
                         commit to a specific recipe — typical for novel or
                         ambiguous subtasks. The goal field IS the task
                         description the selector reads, so make it specific.
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

            newTasks may use taskKind "EXPAND_FROM_DOC" for deterministic
            decomposition when a kind:list / kind:tree / kind:records
            document already carries the plan. taskSpec must then provide
            documentRef={name|path|id} and childTemplate={goal, taskKind,
            taskSpec} with {{item.text}}/{{record.<field>}}/{{index}}/
            {{root.title}}/{{parent.goal}} placeholders. Marvin reads the
            document and creates one child per item without an LLM call —
            ideal when you wrote the outline yourself.
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
    private final DocumentExpander documentExpander;
    /** Used to route recipe-less PLAN-WORKER children through the
     *  selector. PLAN-LLM may emit just a {@code goal} without
     *  picking a recipe — the selector reads project recipes at
     *  spawn-time and picks the best match. Same engine catalog +
     *  inventory as {@code process_create_delegate}. */
    private final de.mhus.vance.brain.delegate.RecipeSelectorService recipeSelector;
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
        Map<String, Object> rootSpec = buildRootTaskSpec(process, rootKind);
        nodeService.createRoot(process.getTenantId(), process.getId(), goal,
                rootKind, rootSpec);
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
     * Reacts to lifecycle events from a worker we spawned. Two flavours:
     *
     * <ul>
     *   <li><b>Synchronous LLM workers</b> (Ford / chat-style): Marvin
     *       drives them inline in {@link #runWorker} and consumes the
     *       reply right there, so the only events that still matter
     *       are surprises (FAILED/STOPPED while node non-terminal).
     *       Successful DONE/SUMMARY for these workers arrives as a
     *       follow-up to the inline-routed output and is recorded as
     *       a mid-flight note.</li>
     *   <li><b>Async workers</b> (Vogon / nested Marvin / Trillian):
     *       {@link #runWorker} only spawns + yields. The node stays
     *       RUNNING until <i>this</i> handler sees the DONE event,
     *       captures the {@code humanSummary} and {@code payload} as
     *       artifacts and marks the node DONE.</li>
     * </ul>
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
            case DONE -> {
                // Async-worker termination — finalise the node here.
                // (Synchronous workers reach this branch too, but their
                // node is already terminal at this point and we'd have
                // bailed at the isTerminalStatus check above.)
                Map<String, Object> artifacts = new LinkedHashMap<>();
                if (event.humanSummary() != null) {
                    artifacts.put("result", event.humanSummary());
                }
                if (event.payload() != null && !event.payload().isEmpty()) {
                    artifacts.put("payload", event.payload());
                }
                nodeService.markDone(node, artifacts);
                log.info("Marvin id='{}' async worker DONE node='{}' worker='{}' "
                                + "summary={} chars",
                        process.getId(), node.getId(), event.sourceProcessId(),
                        event.humanSummary() == null ? 0 : event.humanSummary().length());
            }
            case BLOCKED, STARTED, SUMMARY -> {
                // Mid-flight progress — keep humanSummary as a note on
                // the node so the inspector can see what's happening.
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
            case EXPAND_FROM_DOC -> { runExpandFromDoc(process, ctx, node); yield false; }
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
            // Phase H/L: Recipe-deklarierte Constraints für PLAN-Children.
            // - allowedSubTaskRecipes: Whitelist erlaubter Recipe-Namen
            //   für WORKER-Children.
            // - recipesOnlyViaExpand: Recipes die NUR im EXPAND_FROM_DOC
            //   childTemplate auftauchen dürfen, nicht als direct WORKER.
            // - requiredParamsPerRecipe: pro Recipe Pflicht-Param-Keys
            //   in taskSpec.params (wenn als direct WORKER gespawnt).
            // Verstöße triggern den Re-Prompt-Loop.
            List<String> allowedSubTaskRecipes = readAllowedSubTaskRecipes(process);
            List<String> recipesOnlyViaExpand = readRecipesOnlyViaExpand(process);
            Map<String, List<String>> requiredParamsPerRecipe =
                    readRequiredParamsPerRecipe(process);
            List<String> allowedExpandDocumentRefPaths =
                    readAllowedExpandDocumentRefPaths(process);
            Map<String, List<String>> requiredChildTemplateRecipeParams =
                    readRequiredChildTemplateRecipeParams(process);
            Set<TaskKind> disallowedTaskKinds = readDisallowedTaskKinds(process);
            log.info("Marvin id='{}' PLAN constraints: allowedSubTaskRecipes={}, recipesOnlyViaExpand={}, allowedExpandDocumentRefPaths={}, requiredChildTemplateRecipeParams={}, disallowedTaskKinds={}",
                    process.getId(), allowedSubTaskRecipes,
                    recipesOnlyViaExpand, allowedExpandDocumentRefPaths,
                    requiredChildTemplateRecipeParams, disallowedTaskKinds);
            boolean anyConstraint = allowedSubTaskRecipes != null
                    || recipesOnlyViaExpand != null
                    || requiredParamsPerRecipe != null
                    || allowedExpandDocumentRefPaths != null
                    || requiredChildTemplateRecipeParams != null
                    || disallowedTaskKinds != null;
            int maxPlanCorrections = paramInt(
                    node, "maxPlanCorrections",
                    anyConstraint ? 2 : 0);

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
                    + (allowedSubTaskRecipes == null
                            ? buildRecipeCatalog(process)
                            : buildAllowedRecipesPrompt(allowedSubTaskRecipes))
                    + (allowedExpandDocumentRefPaths == null
                            ? ""
                            : buildAllowedExpandPathsPrompt(allowedExpandDocumentRefPaths))
                    + (customPrompt == null ? "" : "\n\nAdditional instruction:\n" + customPrompt);
            messages.add(UserMessage.from(body));

            String modelAlias = config.provider() + ":" + config.modelName();

            // PLAN call + (optional) content-validation re-prompt loop.
            String text = null;
            List<NodeSpec> children = null;
            String validationError = null;
            for (int attempt = 0; attempt <= maxPlanCorrections; attempt++) {
                long startMs = System.currentTimeMillis();
                ChatResponse response = ai.chatModel().chat(
                        ChatRequest.builder().messages(messages).build());
                llmCallTracker.record(
                        process, response, System.currentTimeMillis() - startMs, modelAlias);
                AiMessage reply = response.aiMessage();
                text = reply == null ? "" : reply.text();
                if (text == null) text = "";

                children = parsePlanChildren(text, maxChildren);
                for (NodeSpec ns : children) {
                    log.info("Marvin id='{}' PLAN attempt {} child kind={} taskSpec={}",
                            process.getId(), attempt, ns.taskKind(), ns.taskSpec());
                }
                if (children.isEmpty()) {
                    validationError = "PLAN produced 0 children";
                } else if (anyConstraint) {
                    validationError = validatePlanChildren(
                            children, allowedSubTaskRecipes,
                            recipesOnlyViaExpand, requiredParamsPerRecipe,
                            allowedExpandDocumentRefPaths,
                            requiredChildTemplateRecipeParams,
                            disallowedTaskKinds);
                } else {
                    validationError = null;
                }
                if (validationError == null) break;

                if (attempt < maxPlanCorrections) {
                    log.info("Marvin id='{}' PLAN correction {}/{}: {}",
                            process.getId(), attempt + 1, maxPlanCorrections, validationError);
                    // Append model's bad output and a corrective user message —
                    // standard format-correction shape.
                    messages.add(AiMessage.from(text));
                    StringBuilder hint = new StringBuilder();
                    hint.append("Your last plan was rejected: ").append(validationError)
                            .append("\n\nRe-emit the plan JSON respecting these rules:\n");
                    if (allowedSubTaskRecipes != null) {
                        hint.append("- WORKER children MUST use ONLY these recipes ")
                                .append("(verbatim names): ")
                                .append(String.join(", ", allowedSubTaskRecipes))
                                .append(".\n");
                    }
                    if (recipesOnlyViaExpand != null && !recipesOnlyViaExpand.isEmpty()) {
                        hint.append("- These recipes MUST appear ONLY inside an ")
                                .append("EXPAND_FROM_DOC childTemplate, never as a ")
                                .append("direct top-level WORKER child: ")
                                .append(String.join(", ", recipesOnlyViaExpand))
                                .append(". (Your last plan placed at least one of them ")
                                .append("at the top level. Move it into an EXPAND_FROM_DOC ")
                                .append("with the appropriate documentRef + childTemplate.)\n");
                    }
                    hint.append("- Every EXPAND_FROM_DOC child MUST declare ")
                            .append("taskSpec.documentRef as a MAP with one of ")
                            .append("name|path|id (NOT a bare string), AND ")
                            .append("taskSpec.childTemplate (with at least ")
                            .append("`taskKind: WORKER` and `recipe: <name>`).\n");
                    if (allowedExpandDocumentRefPaths != null
                            && !allowedExpandDocumentRefPaths.isEmpty()) {
                        hint.append("- documentRef.path MUST be ONE of these ")
                                .append("verbatim strings (no other paths are ")
                                .append("acceptable, no fabrications): ")
                                .append(String.join(", ", allowedExpandDocumentRefPaths))
                                .append(".\n");
                    }
                    hint.append("Example EXPAND_FROM_DOC child shape:\n")
                            .append("  {\n")
                            .append("    \"taskKind\": \"EXPAND_FROM_DOC\",\n")
                            .append("    \"goal\": \"...\",\n")
                            .append("    \"taskSpec\": {\n")
                            .append("      \"documentRef\": { \"path\": \"essay/outline.md\" },\n")
                            .append("      \"treeMode\": \"FLAT\",\n")
                            .append("      \"childTemplate\": {\n")
                            .append("        \"taskKind\": \"WORKER\",\n")
                            .append("        \"recipe\": \"<allowed-recipe-here>\",\n")
                            .append("        \"goal\": \"...\",\n")
                            .append("        \"recipeParams\": { ... }\n")
                            .append("      }\n")
                            .append("    }\n")
                            .append("  }\n");
                    if (requiredParamsPerRecipe != null && !requiredParamsPerRecipe.isEmpty()) {
                        hint.append("- For these recipes, taskSpec.params MUST contain ")
                                .append("the listed keys when used as a direct WORKER:\n");
                        for (Map.Entry<String, List<String>> e
                                : requiredParamsPerRecipe.entrySet()) {
                            hint.append("    ").append(e.getKey()).append(" → ")
                                    .append(String.join(", ", e.getValue())).append("\n");
                        }
                    }
                    if (requiredChildTemplateRecipeParams != null
                            && !requiredChildTemplateRecipeParams.isEmpty()) {
                        hint.append("- For EXPAND_FROM_DOC childTemplate.recipeParams, ")
                                .append("these keys are MANDATORY per child-recipe ")
                                .append("(use the {{index1}} / {{item.text}} placeholders ")
                                .append("so the expander substitutes them per item):\n");
                        for (Map.Entry<String, List<String>> e
                                : requiredChildTemplateRecipeParams.entrySet()) {
                            hint.append("    ").append(e.getKey()).append(" → ")
                                    .append(String.join(", ", e.getValue())).append("\n");
                        }
                    }
                    if (disallowedTaskKinds != null && !disallowedTaskKinds.isEmpty()) {
                        hint.append("- These taskKinds are FORBIDDEN on top-level PLAN ")
                                .append("children: ");
                        boolean first = true;
                        for (TaskKind k : disallowedTaskKinds) {
                            if (!first) hint.append(", ");
                            hint.append(k.name());
                            first = false;
                        }
                        hint.append(". (For aggregation, spawn a WORKER with the ")
                                .append("aggregator recipe instead of the built-in ")
                                .append("AGGREGATE summary.)\n");
                    }
                    hint.append("Other taskKinds (PLAN / EXPAND_FROM_DOC / USER_INPUT / ")
                            .append("AGGREGATE) don't need a recipe and stay free.");
                    messages.add(UserMessage.from(hint.toString()));
                }
            }

            if (validationError != null) {
                nodeService.markFailed(node,
                        "PLAN failed after " + maxPlanCorrections
                                + " corrections — last error: " + validationError
                                + "; model output: " + abbrev(text));
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

    /**
     * Reads the optional {@code allowedSubTaskRecipes} param from the
     * Marvin process's engineParams. When set, the PLAN-stage validates
     * that every WORKER child's {@code taskSpec.recipe} is in this list
     * and re-prompts on violation. Returns {@code null} when the param
     * is absent — in that case classic free-form planning applies (full
     * recipe catalog in the prompt, no content validation).
     */
    private static @Nullable List<String> readAllowedSubTaskRecipes(ThinkProcessDocument process) {
        Object raw = processParam(process, "allowedSubTaskRecipes");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Read the optional {@code recipesOnlyViaExpand} engineParam.
     * Recipes in this list must appear only inside an
     * {@code EXPAND_FROM_DOC} childTemplate, never as a direct
     * top-level WORKER child. Re-prompt when violated.
     */
    private static @Nullable List<String> readRecipesOnlyViaExpand(ThinkProcessDocument process) {
        Object raw = processParam(process, "recipesOnlyViaExpand");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Read the optional {@code requiredParamsPerRecipe} engineParam.
     * Map of {@code recipe-name → [paramKey, …]}. When a direct
     * top-level WORKER child uses a recipe in this map, its
     * {@code taskSpec.params} MUST contain every listed key.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, List<String>> readRequiredParamsPerRecipe(
            ThinkProcessDocument process) {
        Object raw = processParam(process, "requiredParamsPerRecipe");
        if (!(raw instanceof Map<?, ?> m) || m.isEmpty()) return null;
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String recipe) || recipe.isBlank()) continue;
            List<String> required = new ArrayList<>();
            if (e.getValue() instanceof List<?> reqs) {
                for (Object r : reqs) {
                    if (r instanceof String s && !s.isBlank()) required.add(s.trim());
                }
            }
            if (!required.isEmpty()) out.put(recipe.trim(), required);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Read the optional {@code allowedExpandDocumentRefPaths} engineParam.
     * When set, every EXPAND_FROM_DOC child's
     * {@code taskSpec.documentRef.path} MUST be one of the listed
     * verbatim strings — closes the documentRef-fabrication loop in
     * the same way {@code allowedSubTaskRecipes} closed it for
     * recipe names.
     */
    private static @Nullable List<String> readAllowedExpandDocumentRefPaths(
            ThinkProcessDocument process) {
        Object raw = processParam(process, "allowedExpandDocumentRefPaths");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Read the optional {@code requiredChildTemplateRecipeParams}
     * engineParam. Map of {@code child-recipe → [paramKey, …]}. When
     * an EXPAND_FROM_DOC child's {@code childTemplate.recipe} matches
     * a key, its {@code childTemplate.recipeParams} MUST contain
     * every listed key (non-null, non-blank). Closes the
     * "LLM omits recipeParams" loop the essay-pipeline saw in Phase N.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, List<String>>
            readRequiredChildTemplateRecipeParams(ThinkProcessDocument process) {
        Object raw = processParam(process, "requiredChildTemplateRecipeParams");
        if (!(raw instanceof Map<?, ?> m) || m.isEmpty()) return null;
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String recipe) || recipe.isBlank()) continue;
            List<String> required = new ArrayList<>();
            if (e.getValue() instanceof List<?> reqs) {
                for (Object r : reqs) {
                    if (r instanceof String s && !s.isBlank()) required.add(s.trim());
                }
            }
            if (!required.isEmpty()) out.put(recipe.trim(), required);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Read the optional {@code disallowedTaskKinds} engineParam — a
     * list of taskKind names ({@code AGGREGATE}, {@code USER_INPUT},
     * etc.) that the recipe forbids on top-level PLAN children.
     * Forces the LLM into the prescribed shape (e.g. essay-pipeline:
     * use WORKER aggregator_run, not the built-in AGGREGATE summary).
     */
    private static @Nullable Set<TaskKind> readDisallowedTaskKinds(
            ThinkProcessDocument process) {
        Object raw = processParam(process, "disallowedTaskKinds");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        Set<TaskKind> out = new java.util.LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof String s) || s.isBlank()) continue;
            try {
                out.add(TaskKind.valueOf(s.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // unknown kind — skip silently rather than fail recipe load
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Validate the PLAN's children against the configured constraints.
     * Returns null when all constraints hold, an error string listing
     * every violation otherwise.
     *
     * <p>Always-on checks (independent of recipe-supplied constraints):
     * EXPAND_FROM_DOC children must declare {@code taskSpec.documentRef}
     * and a {@code taskSpec.childTemplate} with a {@code recipe} —
     * the empty form is structurally useless and the LLM occasionally
     * emits it after a "move chapter_loop into EXPAND" correction.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable String validatePlanChildren(
            List<NodeSpec> children,
            @Nullable List<String> allowed,
            @Nullable List<String> onlyViaExpand,
            @Nullable Map<String, List<String>> requiredParams,
            @Nullable List<String> allowedExpandPaths,
            @Nullable Map<String, List<String>> requiredChildTemplateParams,
            @Nullable Set<TaskKind> disallowedTaskKinds) {
        List<String> violations = new ArrayList<>();
        for (NodeSpec ns : children) {
            // Always-on check: EXPAND_FROM_DOC must be fully formed.
            // documentRef is a MAP {name|path|id: …}, not a String —
            // Marvin's runExpandFromDoc reads it via paramMap.
            if (ns.taskKind() == TaskKind.EXPAND_FROM_DOC) {
                Map<String, Object> spec = ns.taskSpec() == null
                        ? Map.of() : ns.taskSpec();
                Object docRef = spec.get("documentRef");
                if (!(docRef instanceof Map<?, ?> drm) || drm.isEmpty()) {
                    violations.add("EXPAND_FROM_DOC child '" + ns.goal()
                            + "' is missing taskSpec.documentRef "
                            + "(must be a map with `path` or `id`)");
                } else {
                    Map<String, Object> drMap = (Map<String, Object>) drm;
                    boolean hasPath = drMap.get("path") instanceof String p
                            && !p.isBlank();
                    boolean hasId = drMap.get("id") instanceof String i
                            && !i.isBlank();
                    if (!hasPath && !hasId) {
                        // `name`-only is ambiguous (LLMs love to put
                        // hallucinated short labels there). Require
                        // path or id explicitly to anchor the lookup.
                        violations.add("EXPAND_FROM_DOC child '" + ns.goal()
                                + "' must declare taskSpec.documentRef.path "
                                + "(or .id) — `name` alone is rejected to "
                                + "avoid lookup ambiguity");
                    } else if (hasPath && allowedExpandPaths != null) {
                        // Path-whitelist: even when path is set, reject
                        // fabricated paths the LLM made up. Forces the
                        // model to copy the verbatim recipe string.
                        String path = ((String) drMap.get("path")).trim();
                        if (!allowedExpandPaths.contains(path)) {
                            violations.add("EXPAND_FROM_DOC child '" + ns.goal()
                                    + "' uses documentRef.path '" + path
                                    + "' which is not in the allowed list "
                                    + allowedExpandPaths
                                    + " — copy the path verbatim from the recipe, "
                                    + "do not invent new ones");
                        }
                    }
                }
                Object tmpl = spec.get("childTemplate");
                if (!(tmpl instanceof Map<?, ?> tm) || tm.isEmpty()) {
                    violations.add("EXPAND_FROM_DOC child '" + ns.goal()
                            + "' is missing taskSpec.childTemplate");
                } else {
                    Map<String, Object> tmplMap = (Map<String, Object>) tm;
                    Object tmplRecipe = tmplMap.get("recipe");
                    String tmplRecipeName = null;
                    if (!(tmplRecipe instanceof String rs) || rs.isBlank()) {
                        violations.add("EXPAND_FROM_DOC child '" + ns.goal()
                                + "' is missing taskSpec.childTemplate.recipe");
                    } else {
                        tmplRecipeName = rs.trim();
                        if (allowed != null && !allowed.contains(tmplRecipeName)) {
                            violations.add("EXPAND_FROM_DOC child '" + ns.goal()
                                    + "' uses childTemplate.recipe '" + tmplRecipeName
                                    + "' which is not in the allowed list");
                            tmplRecipeName = null;
                        }
                    }
                    if (tmplRecipeName != null
                            && requiredChildTemplateParams != null) {
                        List<String> req = requiredChildTemplateParams.get(tmplRecipeName);
                        if (req != null && !req.isEmpty()) {
                            Object rpRaw = tmplMap.get("recipeParams");
                            Map<String, Object> rp = rpRaw instanceof Map<?, ?> rpm
                                    ? (Map<String, Object>) rpm : Map.of();
                            for (String key : req) {
                                Object v = rp.get(key);
                                if (v == null
                                        || (v instanceof String vs && vs.isBlank())) {
                                    violations.add("EXPAND_FROM_DOC child '" + ns.goal()
                                            + "' childTemplate (recipe '" + tmplRecipeName
                                            + "') missing required recipeParams['"
                                            + key + "']");
                                }
                            }
                        }
                    }
                }
                continue;
            }
            if (disallowedTaskKinds != null
                    && disallowedTaskKinds.contains(ns.taskKind())) {
                violations.add("child '" + ns.goal()
                        + "' uses disallowed taskKind '" + ns.taskKind()
                        + "' — recipe forbids this kind on top-level PLAN children");
                continue;
            }
            if (ns.taskKind() != TaskKind.WORKER) continue;
            Object recipeRaw = ns.taskSpec() == null ? null : ns.taskSpec().get("recipe");
            String recipe = recipeRaw instanceof String s ? s.trim() : null;
            if (recipe == null || recipe.isBlank()) {
                // Selector-mode: a WORKER without a recipe is allowed
                // when the goal is non-blank. {@code runWorker} calls
                // {@link RecipeSelectorService} at spawn-time to pick
                // a recipe based on the goal text. The PLAN-LLM
                // doesn't have to know recipe names.
                if (ns.goal() == null || ns.goal().isBlank()) {
                    violations.add("WORKER child has neither taskSpec.recipe "
                            + "nor a non-blank goal — give one of them so the "
                            + "spawn knows what to do");
                }
                continue;
            }
            if (allowed != null && !allowed.contains(recipe)) {
                violations.add("WORKER child '" + ns.goal()
                        + "' uses recipe '" + recipe
                        + "' which is not in the allowed list");
                continue;
            }
            if (onlyViaExpand != null && onlyViaExpand.contains(recipe)) {
                violations.add("WORKER child '" + ns.goal()
                        + "' uses recipe '" + recipe
                        + "' which may appear only inside an EXPAND_FROM_DOC "
                        + "childTemplate, not as a direct top-level WORKER");
                continue;
            }
            if (requiredParams != null) {
                List<String> req = requiredParams.get(recipe);
                if (req != null && !req.isEmpty()) {
                    Object paramsRaw = ns.taskSpec().get("params");
                    Map<String, Object> params = paramsRaw instanceof Map<?, ?> mm
                            ? (Map<String, Object>) mm : Map.of();
                    for (String key : req) {
                        Object v = params.get(key);
                        if (v == null
                                || (v instanceof String vs && vs.isBlank())) {
                            violations.add("WORKER child '" + ns.goal()
                                    + "' (recipe '" + recipe
                                    + "') missing required taskSpec.params['"
                                    + key + "']");
                        }
                    }
                }
            }
        }
        return violations.isEmpty() ? null : String.join("; ", violations);
    }

    /** Build the prompt section that lists the allow-listed recipes —
     *  replaces the full catalog when the recipe constrains the plan. */
    private static String buildAllowedRecipesPrompt(List<String> allowed) {
        StringBuilder sb = new StringBuilder();
        sb.append("WORKER recipes you MAY use for `taskSpec.recipe` "
                + "(these are the ONLY allowed values; nothing else "
                + "will be accepted):\n");
        for (String name : allowed) {
            sb.append("  - ").append(name).append("\n");
        }
        sb.append("\nOther taskKinds (PLAN, EXPAND_FROM_DOC, USER_INPUT, "
                + "AGGREGATE) do not need a recipe and remain free-form.\n");
        return sb.toString();
    }

    /** Build the prompt section that lists the allow-listed
     *  EXPAND_FROM_DOC documentRef.path values. Forces the LLM to
     *  copy the recipe-prescribed path verbatim instead of inventing
     *  one. */
    private static String buildAllowedExpandPathsPrompt(List<String> paths) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nFor any EXPAND_FROM_DOC child, "
                + "`taskSpec.documentRef.path` MUST be ONE of these "
                + "verbatim strings — do NOT invent other paths, do "
                + "NOT shorten or rename them, do NOT prefix them with "
                + "`tasks/` or similar:\n");
        for (String p : paths) {
            sb.append("  - ").append(p).append("\n");
        }
        return sb.toString();
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

    // ──────────────────── EXPAND_FROM_DOC ────────────────────

    /**
     * Document-driven decomposition — see
     * {@code specification/marvin-engine.md} §7a. Reads the referenced
     * list/tree/records document via {@link DocumentExpander}, applies
     * the {@code childTemplate} per item and appends the resulting
     * children under this node. No LLM call.
     *
     * <p>Empty document is not a failure (returns 0 children, marks
     * DONE) unless {@code taskSpec.failOnEmpty=true}. Missing template
     * variables resolve to {@code ""} unless
     * {@code taskSpec.failOnMissingField=true}.
     *
     * <p>{@code expandPolicy=PAUSE_BEFORE_EXPAND} (read from the node's
     * {@code taskSpec} or the process's engine-params) injects a
     * USER_INPUT approval sibling <em>before</em> this node, marks
     * the node back to PENDING and yields. The DFS hits the inbox
     * sibling first; once the user approves the EXPAND runs through
     * normally on the next visit (an {@code _approved} flag on the
     * taskSpec stops the policy from re-triggering).
     */
    private void runExpandFromDoc(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            MarvinNodeDocument node) {
        @SuppressWarnings("unchecked")
        Map<String, Object> documentRef = paramMap(node, "documentRef");
        if (documentRef == null || documentRef.isEmpty()) {
            nodeService.markFailed(node,
                    "EXPAND_FROM_DOC missing taskSpec.documentRef");
            log.warn("Marvin id='{}' EXPAND node='{}' missing documentRef — failing",
                    process.getId(), node.getId());
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> childTemplate = paramMap(node, "childTemplate");
        if (childTemplate == null || childTemplate.isEmpty()) {
            nodeService.markFailed(node,
                    "EXPAND_FROM_DOC missing taskSpec.childTemplate");
            log.warn("Marvin id='{}' EXPAND node='{}' missing childTemplate — failing",
                    process.getId(), node.getId());
            return;
        }

        if (shouldPauseForApproval(process, node)) {
            insertApprovalGate(process, node);
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
            log.warn("Marvin id='{}' EXPAND node='{}' failed: {}",
                    process.getId(), node.getId(), ee.getMessage());
            return;
        } catch (RuntimeException e) {
            nodeService.markFailed(node, "EXPAND_FROM_DOC failed: " + e.getMessage());
            log.warn("Marvin id='{}' EXPAND node='{}' unexpected error: {}",
                    process.getId(), node.getId(), e.toString(), e);
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
            log.info("Marvin id='{}' EXPAND node='{}' empty document — DONE without children",
                    process.getId(), node.getId());
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

    /**
     * Walks the {@link DocumentExpander.ExpansionPlan} pre-order,
     * appending direct children under each parent and recursing into
     * nested children. Returns the total number of nodes inserted.
     */
    private int appendExpansionPlan(
            ThinkProcessDocument process, String parentId,
            List<DocumentExpander.TemplatedNode> templated) {
        if (templated.isEmpty()) return 0;
        List<de.mhus.vance.shared.marvin.MarvinNodeService.NodeSpec> directSpecs =
                new ArrayList<>(templated.size());
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

    /**
     * @return {@code true} when the {@code expandPolicy} resolves to
     *         {@code PAUSE_BEFORE_EXPAND} <em>and</em> this node has
     *         not been approved yet (no {@code _approved} marker on
     *         the taskSpec).
     */
    private static boolean shouldPauseForApproval(
            ThinkProcessDocument process, MarvinNodeDocument node) {
        if (paramBool(node, "_approved", false)) return false;
        // Node-level overrides process-level; explicit AUTO wins.
        String policy = paramString(node, "expandPolicy", null);
        if (policy == null) policy = paramString(process, "expandPolicy", null);
        return "PAUSE_BEFORE_EXPAND".equalsIgnoreCase(policy);
    }

    /**
     * Inserts an APPROVAL inbox-item sibling immediately before this
     * EXPAND node, flips the EXPAND back to PENDING and tags it as
     * {@code _approved=true} so the next visit (after the approval
     * lands) runs through to materialization without re-prompting.
     */
    private void insertApprovalGate(
            ThinkProcessDocument process, MarvinNodeDocument expandNode) {
        @SuppressWarnings("unchecked")
        Map<String, Object> docRef = paramMap(expandNode, "documentRef");
        String docDescriptor = describeDocRef(docRef);
        Map<String, Object> taskSpec = new LinkedHashMap<>();
        taskSpec.put("type", "APPROVAL");
        taskSpec.put("title", "Approve outline before expansion");
        taskSpec.put("body", "Marvin is about to expand the document "
                + docDescriptor + " into "
                + (expandNode.getGoal() == null ? "child tasks" : expandNode.getGoal())
                + ". Edit the document now if needed, then approve.");
        de.mhus.vance.shared.marvin.MarvinNodeService.NodeSpec gate =
                new de.mhus.vance.shared.marvin.MarvinNodeService.NodeSpec(
                        "Approve expansion of " + docDescriptor,
                        TaskKind.USER_INPUT,
                        taskSpec);
        nodeService.insertSiblingBefore(process.getTenantId(), expandNode, gate);

        Map<String, Object> updatedSpec = expandNode.getTaskSpec() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(expandNode.getTaskSpec());
        updatedSpec.put("_approved", Boolean.TRUE);
        expandNode.setTaskSpec(updatedSpec);
        expandNode.setStatus(NodeStatus.PENDING);
        expandNode.setStartedAt(null);
        nodeService.save(expandNode);
        log.info("Marvin id='{}' EXPAND node='{}' paused for approval gate (doc={})",
                process.getId(), expandNode.getId(), docDescriptor);
    }

    private static String describeDocRef(@Nullable Map<String, Object> ref) {
        if (ref == null) return "<unknown>";
        Object n = ref.get("name");
        if (n instanceof String s && !s.isBlank()) return "'" + s + "'";
        Object p = ref.get("path");
        if (p instanceof String s && !s.isBlank()) return "'" + s + "'";
        Object id = ref.get("id");
        if (id instanceof String s && !s.isBlank()) return "id=" + s;
        return "<unknown>";
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
            // Selector-mode: PLAN-LLM emitted the child without a
            // recipe binding — pick one based on the node's goal.
            // Same routing as process_create_delegate's selector.
            recipeName = pickRecipeViaSelector(process, node);
            if (recipeName == null) {
                // Selector returned NONE — surface a node-level
                // failure with the rationale baked in (the warn
                // log emitted by the helper has the LLM rationale).
                nodeService.markFailed(node,
                        "WORKER node has no recipe and the selector "
                                + "could not pick one for goal: "
                                + abbrev(node.getGoal()));
                return false;
            }
            log.info("Marvin id='{}' WORKER node='{}' recipe-less spawn — "
                            + "selector picked recipe='{}' for goal='{}'",
                    process.getId(), node.getId(), recipeName,
                    abbrev(node.getGoal()));
        }
        String steerContent = paramString(node, "steerContent", node.getGoal());
        Map<String, Object> recipeParams = paramMap(node, "params");

        ThinkProcessDocument child;
        boolean asyncWorker;
        try {
            AppliedRecipe applied = recipeResolver.apply(
                    process.getTenantId(), ctx.projectId(), recipeName,
                    process.getConnectionProfile(), recipeParams);
            ThinkEngine targetEngine = thinkEngineServiceProvider.getObject()
                    .resolve(applied.engine())
                    .orElseThrow(() -> new IllegalStateException(
                            "Recipe '" + applied.name() + "' references unknown engine '"
                                    + applied.engine() + "'"));
            // Phase I: engines whose `asyncSteer()` is true (Vogon,
            // Marvin, …) don't produce a synchronous LLM reply. Spawn
            // them as long-running children and finalise the WORKER
            // node when their ProcessEvent type=DONE arrives in our
            // pending queue. Synchronous LLM-engines (Ford, …) go
            // through the heritage drive-and-parse path below.
            asyncWorker = targetEngine.asyncSteer();
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
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools(),
                    applied.connectionProfile(),
                    applied.defaultActiveSkills(),
                    applied.allowedSkills() == null
                            ? null : java.util.Set.copyOf(applied.allowedSkills()));
            nodeService.setSpawnedProcessId(node, child.getId());
            thinkEngineServiceProvider.getObject().start(child);
            log.info("Marvin id='{}' WORKER node='{}' spawned child='{}' recipe='{}' async={}",
                    process.getId(), node.getId(), child.getId(), applied.name(), asyncWorker);
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

        // Async worker (Vogon / nested Marvin / Trillian / …): start
        // and yield. Node stays RUNNING; handleProcessEvent finalises
        // it when the child's DONE/FAILED/STOPPED event lands in our
        // pending queue.
        if (asyncWorker) {
            return false;
        }

        // Synchronous LLM worker (Ford / chat-style): drive the worker
        // — including up to MAX_OUTPUT_CORRECTIONS schema-correction
        // round-trips — and route the final output.
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
            // One-shot synchronous worker: stop it now, regardless of
            // outcome, so it doesn't linger as a READY process
            // consuming a session slot. The STOPPED-event will arrive
            // in Marvin's pending queue and be ignored (node is now
            // terminal). Async workers manage their own lifecycle —
            // we let them drive themselves to DONE.
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

    /**
     * Builds the root node's {@code taskSpec} from process-level
     * engine-params. For {@code EXPAND_FROM_DOC} we lift
     * {@code rootDocumentRef} → {@code documentRef},
     * {@code rootChildTemplate} → {@code childTemplate} and
     * {@code rootTreeMode} → {@code treeMode} so a recipe can spawn
     * a doc-driven Marvin via params alone (see spec §7a.3 (c)).
     * Other root kinds get an empty spec — their handlers carry no
     * required fields beyond {@code goal} for the root.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> buildRootTaskSpec(
            ThinkProcessDocument process, TaskKind kind) {
        if (kind != TaskKind.EXPAND_FROM_DOC) return null;
        Map<String, Object> p = process.getEngineParams();
        if (p == null) return null;
        Map<String, Object> spec = new LinkedHashMap<>();
        Object docRef = p.get("rootDocumentRef");
        if (docRef instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k) copy.put(k, e.getValue());
            }
            spec.put("documentRef", copy);
        } else if (docRef instanceof String s && !s.isBlank()) {
            // Convenience shorthand: a bare string is treated as the
            // document name within the current project.
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("name", s);
            spec.put("documentRef", ref);
        }
        Object tmpl = p.get("rootChildTemplate");
        if (tmpl instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k) copy.put(k, e.getValue());
            }
            spec.put("childTemplate", copy);
        }
        Object treeMode = p.get("rootTreeMode");
        if (treeMode instanceof String s && !s.isBlank()) spec.put("treeMode", s);
        return spec.isEmpty() ? null : spec;
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

    /**
     * Routes a recipe-less WORKER node through {@link
     * de.mhus.vance.brain.delegate.RecipeSelectorService}. Returns
     * the picked recipe name on MATCH; null on NONE / failure.
     * Caller decides how to fail the node.
     *
     * <p>Cost: one synchronous selector LLM-call per recipe-less
     * spawn. PLAN-LLMs that pre-emit recipes via
     * {@code allowedSubTaskRecipes} bypass this path entirely.
     */
    private @Nullable String pickRecipeViaSelector(
            ThinkProcessDocument process, MarvinNodeDocument node) {
        String goal = node.getGoal();
        if (goal == null || goal.isBlank()) {
            log.warn("Marvin id='{}' WORKER node='{}' has no goal — "
                            + "cannot run selector",
                    process.getId(), node.getId());
            return null;
        }
        try {
            de.mhus.vance.brain.delegate.RecipeSelectorService.Result r =
                    recipeSelector.select(process, goal);
            if (r.decision()
                    == de.mhus.vance.brain.delegate.RecipeSelectorService.Result.Decision.MATCH) {
                return r.recipeName();
            }
            log.warn("Marvin id='{}' WORKER node='{}' selector returned NONE: {}",
                    process.getId(), node.getId(), r.rationale());
            return null;
        } catch (RuntimeException e) {
            log.warn("Marvin id='{}' WORKER node='{}' selector call failed: {}",
                    process.getId(), node.getId(), e.toString());
            return null;
        }
    }
}
