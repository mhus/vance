package de.mhus.vance.brain.marvin;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.marvin.NodeStatus;
import de.mhus.vance.api.marvin.TaskKind;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.BundledRecipe;
import de.mhus.vance.brain.recipe.BundledRecipeRegistry;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.marvin.MarvinNodeDocument;
import de.mhus.vance.shared.marvin.MarvinNodeService;
import de.mhus.vance.shared.marvin.MarvinNodeService.NodeSpec;
import de.mhus.vance.shared.settings.SettingService;
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

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "whoami",
            "recipe_list",
            "recipe_describe",
            "docs_list",
            "docs_read");

    private static final String SETTINGS_REF_TYPE = "tenant";
    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";

    private final MarvinNodeService nodeService;
    private final MarvinProperties properties;
    private final InboxItemService inboxItemService;
    private final ThinkProcessService thinkProcessService;
    private final RecipeResolver recipeResolver;
    private final BundledRecipeRegistry bundledRecipeRegistry;
    private final AiModelResolver aiModelResolver;
    private final ObjectMapper objectMapper;
    private final ProcessEventEmitter eventEmitter;
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
                + "delegates work to Zaphod-style workers, asks the user via "
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
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
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
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
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
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.STOPPED);
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
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
            eventEmitter.scheduleTurn(process.getId());
        } catch (RuntimeException e) {
            log.warn("Marvin runTurn failed id='{}': {}",
                    process.getId(), e.toString(), e);
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.STALE);
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
        }
    }

    private void handleProcessEvent(
            ThinkProcessDocument process, SteerMessage.ProcessEvent event) {
        Optional<MarvinNodeDocument> nodeOpt =
                nodeService.findBySpawnedProcessId(event.sourceProcessId());
        if (nodeOpt.isEmpty()) {
            log.warn("Marvin id='{}' got ProcessEvent for unknown spawn='{}' (type={})",
                    process.getId(), event.sourceProcessId(), event.type());
            return;
        }
        MarvinNodeDocument node = nodeOpt.get();
        switch (event.type()) {
            case DONE -> {
                Map<String, Object> artifacts = new LinkedHashMap<>();
                if (event.humanSummary() != null) {
                    artifacts.put("workerSummary", event.humanSummary());
                }
                if (event.payload() != null) {
                    artifacts.putAll(event.payload());
                }
                nodeService.markDone(node, artifacts);
                log.info("Marvin id='{}' worker DONE node='{}' worker='{}'",
                        process.getId(), node.getId(), event.sourceProcessId());
            }
            case FAILED, STOPPED -> {
                String reason = "worker " + event.type().name().toLowerCase()
                        + (event.humanSummary() == null ? "" : ": " + event.humanSummary());
                nodeService.markFailed(node, reason);
                log.info("Marvin id='{}' worker {} node='{}' worker='{}'",
                        process.getId(), event.type(), node.getId(), event.sourceProcessId());
            }
            case BLOCKED, STARTED, SUMMARY -> {
                // Mid-flight notes — keep the node RUNNING; just record.
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
            AiChatConfig config = resolveAiConfig(process, ctx.settingService(), aiModelResolver);
            AiChat ai = ctx.aiModelService().createChat(config, AiChatOptions.builder().build());

            int maxChildren = paramInt(node, "maxChildren", properties.getPlanMaxChildren());
            String customPrompt = paramString(node, "prompt", null);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(PLAN_SYSTEM_PROMPT));
            String body = "ROOT goal of the Marvin process:\n"
                    + nullSafe(process.getGoal()) + "\n\n"
                    + "PARENT goal of the node you are decomposing:\n"
                    + node.getGoal() + "\n\n"
                    + "maxChildren: " + maxChildren + "\n\n"
                    + buildRecipeCatalog()
                    + (customPrompt == null ? "" : "\n\nAdditional instruction:\n" + customPrompt);
            messages.add(UserMessage.from(body));

            ChatResponse response = ai.chatModel().chat(
                    ChatRequest.builder().messages(messages).build());
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
     * Lists the bundled recipes that exist on this brain so the
     * planner picks real names instead of inventing
     * {@code RESEARCH_AND_SUMMARIZE}-style fictions. We don't include
     * tenant- or project-recipes here in v1 — adding them would
     * require a project-scoped lookup, and they're discoverable via
     * {@code recipe_list} at runtime if a worker needs them.
     */
    private String buildRecipeCatalog() {
        if (bundledRecipeRegistry.size() == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Available WORKER recipes (use one of these for "
                + "every WORKER child's `taskSpec.recipe`; never invent "
                + "recipe names):\n");
        for (BundledRecipe r : bundledRecipeRegistry.all()) {
            // Skip Marvin / Arthur / engine-default themselves — they
            // aren't sensible workers for a Marvin-spawned child.
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
     * Spawns the worker referenced by the node's taskSpec.
     *
     * @return {@code true} if a worker was spawned and the node is now
     *         RUNNING (waiting for the worker's ProcessEvent);
     *         {@code false} if the spawn failed synchronously and the
     *         node is FAILED — in that case {@code runTurn} should
     *         continue with the next sibling rather than yield.
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
        try {
            AppliedRecipe applied = recipeResolver.apply(
                    process.getTenantId(), ctx.projectId(), recipeName, recipeParams);
            ThinkEngine targetEngine = thinkEngineServiceProvider.getObject()
                    .resolve(applied.engine())
                    .orElseThrow(() -> new IllegalStateException(
                            "Recipe '" + applied.name() + "' references unknown engine '"
                                    + applied.engine() + "'"));
            String childName = "marvin-" + node.getId();
            ThinkProcessDocument child = thinkProcessService.create(
                    process.getTenantId(),
                    process.getSessionId(),
                    childName,
                    targetEngine.name(),
                    targetEngine.version(),
                    /*title*/ "Marvin worker for node " + node.getId(),
                    /*goal*/ node.getGoal(),
                    /*parentProcessId*/ process.getId(),
                    applied.params(),
                    applied.name(),
                    applied.promptOverride(),
                    applied.promptOverrideSmall(),
                    applied.promptMode(),
                    applied.intentCorrection(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools());
            nodeService.setSpawnedProcessId(node, child.getId());
            // Worker engine.start performs its greeting/init; for chat-style
            // workers (Zaphod) the first user input arrives next.
            thinkEngineServiceProvider.getObject().start(child);
            // Send the initial steer so the worker actually starts the task.
            thinkProcessService.appendPending(child.getId(),
                    de.mhus.vance.shared.thinkprocess.PendingMessageDocument.builder()
                            .type(de.mhus.vance.shared.thinkprocess.PendingMessageType.USER_CHAT_INPUT)
                            .at(java.time.Instant.now())
                            .fromUser("marvin:" + process.getId())
                            .content(steerContent)
                            .build());
            // Wake the worker on its OWN lane — running runTurn here
            // would occupy Marvin's lane while the worker LLM-loops.
            eventEmitter.scheduleTurn(child.getId());
            log.info("Marvin id='{}' WORKER node='{}' spawned child='{}' recipe='{}'",
                    process.getId(), node.getId(), child.getId(), applied.name());
            return true;
        } catch (RecipeResolver.UnknownRecipeException ure) {
            nodeService.markFailed(node, "Unknown recipe: " + recipeName);
            log.warn("Marvin id='{}' WORKER node='{}' unknown recipe '{}' — failing (next sibling continues)",
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

            AiChatConfig config = resolveAiConfig(process, ctx.settingService(), aiModelResolver);
            AiChat ai = ctx.aiModelService().createChat(config, AiChatOptions.builder().build());

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(AGGREGATE_SYSTEM_PROMPT));
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
            ChatResponse response = ai.chatModel().chat(
                    ChatRequest.builder().messages(messages).build());
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
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.DONE);
            return;
        }
        boolean running = nodeService.hasRunningNodes(process.getId());
        boolean waiting = nodeService.hasWaitingNodes(process.getId());
        if (waiting && !running) {
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
            return;
        }
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
    }

    private boolean nodeBudgetExceeded(ThinkProcessDocument process) {
        return nodeService.listAll(process.getId()).size() > properties.getMaxTreeNodes();
    }

    // ──────────────────── Config resolve (mirrors Zaphod) ────────────────────

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
        AiModelResolver.Resolved resolved = modelResolver.resolveOrDefault(spec, tenantId);
        String apiKeySetting = String.format(SETTING_PROVIDER_API_KEY_FMT, resolved.provider());
        String apiKey = settings.getDecryptedPassword(
                tenantId, SETTINGS_REF_TYPE, tenantId, apiKeySetting);
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
