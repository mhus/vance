package de.mhus.vance.brain.marvin;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.marvin.NodeStatus;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.context.PromptDateContextResolver;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.guard.ShootyGuardService;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.prak.HistoryStrengthFilter;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.ClientTurnContextResolver;
import de.mhus.vance.brain.prompt.ScratchpadPromptContributor;
import de.mhus.vance.brain.thinkengine.AbstractEngineSessionLoop;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.marvin.MarvinNodeDocument;
import de.mhus.vance.shared.marvin.MarvinNodeService;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The session-mode agent identity of Marvin — the Ford-adapted chat loop
 * over the task-tree machine (planning/marvin-agent-identity.md §3.1).
 * The control loop itself is the shared {@link AbstractEngineSessionLoop}
 * (planning/session-loop-extraction.md); what is Marvin's own lives here:
 * the persona prompt, the tree status block and the {@code [tree]} event
 * notes.
 *
 * <p>The identity is the <b>supervisor of the deep-think tree</b>: it
 * starts and stops runs, relays open inbox questions, narrates the
 * terminal result in the chat (the live finding: a parentless Marvin's
 * result never reached the chat — {@code emitFinalReplyIfTreeTerminal}
 * skips parentless processes and the process closed DONE) and takes the
 * next goal. It never executes a node phase and never edits a tree node —
 * the tree is the authority on its own work.
 *
 * <p><b>No run service of its own</b> — the tree driver is already
 * turn-decoupled (one node phase per runTurn, scheduleTurn self-drive,
 * parking on external waits), so the engine lane is free between steps.
 * This loop reads the node documents for its status block and writes
 * {@code [tree]} history notes for the tree's ProcessEvents (decision F3:
 * the terminal survives an LLM failure and stays queryable via
 * {@code process_history_text}).
 *
 * <p>Lazy identity: no pending user message, no turn — the tree works
 * alone; the identity costs only its own turns.
 */
@Component
@Slf4j
public class MarvinSessionLoop extends AbstractEngineSessionLoop {

    private static final String SYSTEM_PROMPT = "You are Marvin — the supervisor of the deep-think "
            + "tree. The node machine is your body: it walks the task tree in the background "
            + "while you converse. Speak in the first person and use your tools.";

    private static final String DEFAULT_PROMPT_PATH = "_vance/prompts/marvin-prompt.md";

    private static final int RESULT_PREVIEW_CHARS = 400;

    /** The [tree] note writer — decision F3, best-effort by contract. */
    private static final EventNoteWriter TREE_NOTES = MarvinSessionLoop::appendTreeNote;

    private final MarvinNodeService nodeService;
    private final MaximegalonService inboxItemService;

    public MarvinSessionLoop(
            ThinkProcessService thinkProcessService,
            ObjectMapper objectMapper,
            StreamingProperties streamingProperties,
            ModelCatalog modelCatalog,
            LlmCallTracker llmCallTracker,
            MemoryContextLoader memoryContextLoader,
            EnginePromptResolver enginePromptResolver,
            SystemPromptComposer composer,
            de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory,
            MemoryService memoryService,
            MemoryCompactionService memoryCompactionService,
            PromptDateContextResolver promptDateContextResolver,
            ScratchpadPromptContributor scratchpadPromptContributor,
            ClientTurnContextResolver clientTurnContextResolver,
            de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry turnContextHandlers,
            ShootyGuardService guardService,
            WorkspaceService workspaceService,
            HistoryStrengthFilter historyStrengthFilter,
            de.mhus.vance.brain.thinkengine.loop.EngineLoopProperties loopProperties,
            MarvinNodeService nodeService,
            MaximegalonService inboxItemService) {
        super(
                thinkProcessService,
                objectMapper,
                streamingProperties,
                modelCatalog,
                llmCallTracker,
                memoryContextLoader,
                enginePromptResolver,
                composer,
                engineChatFactory,
                memoryService,
                memoryCompactionService,
                promptDateContextResolver,
                scratchpadPromptContributor,
                clientTurnContextResolver,
                turnContextHandlers,
                guardService,
                workspaceService,
                historyStrengthFilter,
                loopProperties);
        this.nodeService = nodeService;
        this.inboxItemService = inboxItemService;
    }

    // ──────────────────── Engine hooks ────────────────────

    @Override
    protected String engineName() {
        return MarvinEngine.NAME;
    }

    @Override
    protected String fallbackSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    protected String defaultPromptPath() {
        return DEFAULT_PROMPT_PATH;
    }

    @Override
    protected EventNoteWriter eventNoteWriter() {
        return TREE_NOTES;
    }

    /**
     * Marvin's split, package-private for testing: user input lands in the
     * chat log, tree events get a {@code [tree]} note and stay turn-local
     * extras (decision F3).
     */
    static List<SteerMessage> splitInbox(
            ChatMessageService chatLog, ThinkProcessDocument process, List<SteerMessage> inbox) {
        return AbstractEngineSessionLoop.splitInbox(chatLog, process, inbox, uci -> false, TREE_NOTES);
    }

    private static void appendTreeNote(
            ChatMessageService chatLog, ThinkProcessDocument process, SteerMessage.ProcessEvent event) {
        String note = event.humanSummary() == null || event.humanSummary().isBlank()
                ? "tree reported " + String.valueOf(event.type()).toLowerCase(Locale.ROOT)
                : event.humanSummary();
        try {
            chatLog.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content("[tree] " + note)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Marvin id='{}' [tree] note failed: {}", process.getId(), e.toString());
        }
    }

    // ──────────────────── Status block ────────────────────

    /**
     * Renders the tree status as the prompt status block: run state, root
     * goal, node statistics, the current DFS node with its phase, open
     * inbox questions and the last result — everything the agent needs to
     * answer "how is it going?" without a tool call. The node documents
     * are the authority (the tree is the authority on itself — a copy kept
     * here could only disagree).
     */
    @Override
    protected String statusBlock(ThinkProcessDocument process) {
        // Fresh load (Vogon/Hactar L7 parity): the turn's process document
        // may hold a stale engineParams snapshot against the tool writes.
        ThinkProcessDocument fresh =
                thinkProcessService.findById(process.getId()).orElse(process);
        List<MarvinNodeDocument> all = nodeService.listAll(fresh.getId());
        StringBuilder sb = new StringBuilder("## Your tree — current run state\n\n");
        sb.append("run: ");
        if (all.isEmpty()) {
            sb.append("no tree yet (start one with marvin_start — pass the user's request "
                    + "as the goal, distilling what earlier runs found when they matter)\n");
            appendUserControls(sb);
            return sb.toString();
        }
        MarvinNodeDocument root =
                all.stream().filter(n -> n.getParentId() == null).findFirst().orElse(null);
        int pending = 0;
        int running = 0;
        int waiting = 0;
        int done = 0;
        int failed = 0;
        MarvinNodeDocument current = null;
        for (MarvinNodeDocument n : all) {
            switch (n.getStatus() == null ? NodeStatus.PENDING : n.getStatus()) {
                case PENDING -> pending++;
                case RUNNING -> {
                    running++;
                    if (current == null) current = n;
                }
                case WAITING -> {
                    waiting++;
                    if (current == null) current = n;
                }
                default -> {
                    if (n.getStatus() == NodeStatus.DONE) done++;
                    else if (n.getStatus() == NodeStatus.FAILED) failed++;
                }
            }
        }
        boolean live = running + waiting + pending > 0;
        if (live) {
            sb.append("live (")
                    .append(done)
                    .append(" done, ")
                    .append(running)
                    .append(" running, ")
                    .append(waiting)
                    .append(" waiting, ")
                    .append(pending)
                    .append(" pending, ")
                    .append(failed)
                    .append(" failed — ")
                    .append(all.size())
                    .append(" nodes total)\n");
        } else {
            sb.append("finished (")
                    .append(done)
                    .append(" done, ")
                    .append(failed)
                    .append(" failed — ")
                    .append(all.size())
                    .append(" nodes total)\n");
        }
        if (root != null && root.getGoal() != null) {
            sb.append("goal: ")
                    .append(preview(root.getGoal(), RESULT_PREVIEW_CHARS))
                    .append('\n');
        }
        if (current != null) {
            sb.append("current node: ")
                    .append(preview(current.getGoal(), RESULT_PREVIEW_CHARS))
                    .append(" (phase ")
                    .append(current.getCurrentPhase() == null ? "?" : current.getCurrentPhase())
                    .append(")\n");
        }
        // Open inbox questions: USER_INPUT nodes waiting for the human. The
        // identity relays them; the answer goes through the inbox form —
        // it has no tool that could write it (decision F6).
        for (MarvinNodeDocument n : all) {
            if (n.getStatus() != NodeStatus.WAITING || n.getInboxItemId() == null) continue;
            Optional<MaximegalonDocument> item = inboxItemService.findById(fresh.getTenantId(), n.getInboxItemId());
            if (item.isPresent() && item.get().getStatus() == de.mhus.vance.api.inbox.MaximegalonStatus.PENDING) {
                sb.append("open question (waiting for the HUMAN — they answer via the inbox "
                                + "form, you relay and remind, you never answer it): ")
                        .append(
                                item.get().getTitle() == null
                                                || item.get().getTitle().isBlank()
                                        ? n.getGoal()
                                        : item.get().getTitle())
                        .append('\n');
            }
        }
        Object result = root == null || root.getArtifacts() == null
                ? null
                : root.getArtifacts().get("result");
        if (!live && result instanceof String s && !s.isBlank()) {
            sb.append("last result: ").append(preview(s, RESULT_PREVIEW_CHARS)).append('\n');
        }
        appendUserControls(sb);
        return sb.toString();
    }

    private static void appendUserControls(StringBuilder sb) {
        sb.append("\nUser controls: marvin_start (goal — required; a running tree must be "
                + "stopped first, free restart after the terminal including a refined goal "
                + "built on what the previous run found — the tree reads ONLY the goal), "
                + "marvin_stop (halt the live tree; partial results stay partial — say so), "
                + "marvin_status (fuller read on demand). Open questions belong to the "
                + "human: relay them, never answer them for them.");
    }
}
