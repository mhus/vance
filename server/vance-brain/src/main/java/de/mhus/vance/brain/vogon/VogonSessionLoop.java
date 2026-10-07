package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.magrathea.MagratheaProcessDto;
import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.context.PromptDateContextResolver;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.guard.ShootyGuardService;
import de.mhus.vance.brain.magrathea.MagratheaGateChatAnswerService;
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
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The session-mode agent identity of Vogon — the Ford-adapted chat loop over
 * the Magrathea runner (planning/vogon-agent-identity.md §3.1). The control
 * loop itself is the shared {@link AbstractEngineSessionLoop}
 * (planning/session-loop-extraction.md); what is Vogon's own lives here:
 * the persona prompt, the journal status block and the {@code [run]} event
 * notes.
 *
 * <p>The identity is the <b>operator of written plans</b>: it starts and
 * stops runs, reports what a run does, and routes plan changes to
 * Slartibartfast — it never executes a state itself (the runner is the
 * machinery) and never edits a plan document (the author is Slart). An open
 * gate belongs to the human: the mechanical chat-answer fast-path runs
 * BEFORE this loop ({@code VogonEngine}, decision F1) and this identity has
 * no tool that could answer a gate.
 *
 * <p><b>No run service of its own</b> — unlike Hactar, where the phase
 * machine had to move to a background service first, the Magrathea runner
 * already lives behind the engine lane. This loop only reads the journal
 * projection for its status block and writes {@code [run]} history notes
 * for the run's ProcessEvents (decision F3: the note survives an LLM
 * failure, the pending event alone does not).
 *
 * <p>The run status is part of the prompt (status block, refreshed per
 * turn) — the agent knows without a tool call where the run stands,
 * including the open gate.
 *
 * <p>Lazy identity: no pending message, no turn — a session-mode process
 * without traffic makes zero LLM calls (the runner works alone).
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@Slf4j
public class VogonSessionLoop extends AbstractEngineSessionLoop {

    private static final String SYSTEM_PROMPT = "You are Vogon — the operator of written plans. "
            + "The runner is your machinery: it drives the plan's states in the background "
            + "while you converse. Speak in the first person and use your tools.";

    private static final String DEFAULT_PROMPT_PATH = "_vance/prompts/vogon-prompt.md";

    private static final int RESULT_PREVIEW_CHARS = 400;

    /** The [run] note writer — decision F3, best-effort by contract. */
    private static final EventNoteWriter RUN_NOTES = VogonSessionLoop::appendRunNote;

    private final MagratheaStateProjector projector;
    private final MagratheaGateChatAnswerService gateChatAnswerService;

    public VogonSessionLoop(
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
            MagratheaStateProjector projector,
            MagratheaGateChatAnswerService gateChatAnswerService) {
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
                historyStrengthFilter);
        this.projector = projector;
        this.gateChatAnswerService = gateChatAnswerService;
    }

    // ──────────────────── Engine hooks ────────────────────

    @Override
    protected String engineName() {
        return VogonEngine.NAME;
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
        return RUN_NOTES;
    }

    /**
     * Vogon's split, package-private for testing: user input lands in the
     * chat log, run events get a {@code [run]} note and stay turn-local
     * extras (decision F3).
     */
    static List<SteerMessage> splitInbox(
            ChatMessageService chatLog, ThinkProcessDocument process, List<SteerMessage> inbox) {
        return AbstractEngineSessionLoop.splitInbox(chatLog, process, inbox, uci -> false, RUN_NOTES);
    }

    private static void appendRunNote(
            ChatMessageService chatLog, ThinkProcessDocument process, SteerMessage.ProcessEvent event) {
        String note = event.humanSummary() == null || event.humanSummary().isBlank()
                ? "run reported " + String.valueOf(event.type()).toLowerCase(Locale.ROOT)
                : event.humanSummary();
        try {
            chatLog.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content("[run] " + note)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Vogon id='{}' [run] note failed: {}", process.getId(), e.toString());
        }
    }

    // ──────────────────── Status block ────────────────────

    /**
     * Renders the run status as the prompt status block: run state, plan
     * name, current state, elapsed time, open gate, last result — everything
     * the agent needs to answer "how is it going?" without a tool call. The
     * journal projection is the authority (the run is the authority on
     * itself; a copy kept here could only disagree — same rule as
     * {@code summarizeForParent}).
     */
    @Override
    protected String statusBlock(ThinkProcessDocument process) {
        // Fresh load (Hactar Review-16 L7 parity): the turn's process
        // document may hold a stale engineParams snapshot — rememberRunId
        // wrote the run id after this document was loaded.
        ThinkProcessDocument fresh =
                thinkProcessService.findById(process.getId()).orElse(process);
        String runId = VogonBaseTool.runId(fresh);
        StringBuilder sb = new StringBuilder("## Your run — current plan state\n\n");
        sb.append("run: ");
        if (runId == null) {
            sb.append("no plan yet (start one with vogon_start — the user may name the plan, "
                    + "a document path, or just describe what should happen)\n");
        } else {
            Optional<MagratheaProcessDto> run = projector.project(fresh.getTenantId(), fresh.getProjectId(), runId);
            if (run.isEmpty()) {
                sb.append("run '").append(runId).append("' left no journal (pruned or foreign)\n");
            } else {
                MagratheaProcessDto dto = run.get();
                sb.append(describeRun(dto)).append('\n');
                sb.append("plan: ")
                        .append(dto.getWorkflowName())
                        .append(" (run ")
                        .append(runId)
                        .append(")\n");
                if (dto.getCurrentState() != null) {
                    sb.append("current state: ").append(dto.getCurrentState()).append('\n');
                }
                if (isTerminal(dto.getStatus())
                        && dto.getResult() != null
                        && !dto.getResult().isEmpty()) {
                    sb.append("last result: ")
                            .append(preview(renderValue(dto.getResult()), RESULT_PREVIEW_CHARS))
                            .append('\n');
                }
                gateChatAnswerService
                        .findOpenGateItem(fresh.getTenantId(), runId)
                        .ifPresent(item -> {
                            sb.append("open gate: ").append(item.getType());
                            if (item.getTitle() != null && !item.getTitle().isBlank()) {
                                sb.append(" '").append(item.getTitle()).append("'");
                            }
                            sb.append(" — waiting for the HUMAN to answer (in this conversation or via the inbox "
                                    + "form). You explain and remind; you never answer it for them.\n");
                        });
            }
        }
        sb.append("\nUser controls: vogon_start (workflow name or workflowPath, plus plan params — a new "
                + "start requires the previous run stopped), vogon_stop (halt the run; partial task side "
                + "effects are possible), vogon_status (fuller read on demand). Plan changes go through "
                + "a Slartibartfast spawn (current plan + the user's request → new plan version) — you "
                + "never edit a plan document yourself.");
        return sb.toString();
    }

    private static String describeRun(MagratheaProcessDto dto) {
        MagratheaRunStatus status = dto.getStatus();
        if (status == null) return "unknown";
        return switch (status) {
            case RUNNING -> "live (" + elapsedSeconds(dto) + "s elapsed)";
            case PAUSED -> "paused (interrupted mid-run — resume it or start fresh)";
            case DONE -> "finished";
            case FAILED -> "failed";
            case TERMINATED -> "stopped";
        };
    }

    private static boolean isTerminal(@Nullable MagratheaRunStatus status) {
        return status == MagratheaRunStatus.DONE
                || status == MagratheaRunStatus.FAILED
                || status == MagratheaRunStatus.TERMINATED;
    }

    private static String elapsedSeconds(MagratheaProcessDto dto) {
        if (dto.getCreatedAt() == null) return "?";
        Duration d = Duration.between(dto.getCreatedAt(), Instant.now());
        return String.valueOf(d.isNegative() ? 0 : d.getSeconds());
    }

    private String renderValue(Object value) {
        if (value == null) return "(no result)";
        try {
            return "```json\n" + objectMapper.writeValueAsString(value) + "\n```";
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }
}
