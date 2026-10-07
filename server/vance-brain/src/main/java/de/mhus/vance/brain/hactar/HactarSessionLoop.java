package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
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
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The session-mode agent identity of Hactar — the Ford-adapted chat loop
 * over the script-execution machine (planning/hactar-agent-identity.md).
 * The control loop itself is the shared
 * {@link AbstractEngineSessionLoop} (planning/session-loop-extraction.md);
 * what is Hactar's own lives here: the persona prompt, the phase-machine
 * status block with its live console tail, and the wakeup-split that
 * keeps the run service's synthetic wake triggers out of the transcript.
 *
 * <p>The identity is the <b>script itself</b> (persona experiment: body =
 * phase machine, voice = console output). The run service writes its own
 * {@code [run]} history notes — this loop adds no event notes of its own
 * and skips the drained wakeup copies (the pending copy is only the
 * trigger).
 *
 * <p>Lazy identity: no pending message, no turn — a session-mode process
 * without traffic makes zero LLM calls.
 */
@Component
public class HactarSessionLoop extends AbstractEngineSessionLoop {

    private static final String SYSTEM_PROMPT = "You are Hactar — the script itself. "
            + "The phase machine is your body, the console output is your voice; speak "
            + "in the first person and use your tools.";

    private static final String DEFAULT_PROMPT_PATH = "_vance/prompts/hactar-prompt.md";

    private static final int RESULT_PREVIEW_CHARS = 400;

    private static final int PROGRESS_TAIL = 5;

    /**
     * Run wakeups are skipped: the run service already wrote its "[run]"
     * note to the history, and the pending copy carries
     * {@link HactarRunService#WAKEUP_SENDER} as the wake trigger only —
     * appending it again would show every wakeup twice and mislabel run
     * output as the user's.
     */
    static final Predicate<SteerMessage.UserChatInput> SKIP_WAKEUPS =
            uci -> HactarRunService.WAKEUP_SENDER.equals(uci.fromUser());

    private final HactarRunService runService;
    private final HactarStateStore stateStore;
    private final HactarProgressRing progressRing;
    private final HactarConsoleLog consoleLog;

    public HactarSessionLoop(
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
            HactarRunService runService,
            HactarStateStore stateStore,
            HactarProgressRing progressRing,
            HactarConsoleLog consoleLog) {
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
        this.runService = runService;
        this.stateStore = stateStore;
        this.progressRing = progressRing;
        this.consoleLog = consoleLog;
    }

    // ──────────────────── Engine hooks ────────────────────

    @Override
    protected String engineName() {
        return HactarEngine.NAME;
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
    protected Predicate<SteerMessage.UserChatInput> skipUserInput() {
        return SKIP_WAKEUPS;
    }

    /**
     * Hactar's split, package-private for testing: run wakeups are
     * skipped (the run service wrote its own history note), user input
     * lands in the chat log, everything non-UCI stays turn-local — this
     * loop writes no event notes of its own.
     */
    static List<SteerMessage> splitInbox(
            de.mhus.vance.shared.chat.ChatMessageService chatLog,
            ThinkProcessDocument process,
            List<SteerMessage> inbox) {
        return AbstractEngineSessionLoop.splitInbox(chatLog, process, inbox, SKIP_WAKEUPS, null);
    }

    // ──────────────────── Status block ────────────────────

    /**
     * Renders the run status as the prompt status block: phase, script,
     * run state, elapsed time, progress ring tail, last result/failure —
     * everything the agent needs to answer "how is it going?" without a
     * tool call.
     */
    @Override
    protected String statusBlock(ThinkProcessDocument process) {
        String processId = process.getId();
        boolean running = runService.isRunning(processId);
        // Fresh load (Review-16 L7): the turn's process document may hold
        // a stale engineParams snapshot — the background runner persists
        // phase transitions on its own document instance. One findById
        // per rendered status block keeps the phase current (the same
        // freshness hactar_status gets via HactarBaseTool.process()).
        HactarState s = stateStore.load(thinkProcessService.findById(processId).orElse(process));
        StringBuilder sb = new StringBuilder("## You — current run state (you are the script, this is your body)\n\n");
        sb.append("run: ");
        if (running) {
            Long startedAt = runService.runStartedAtMs(processId);
            long elapsed = startedAt == null ? 0 : System.currentTimeMillis() - startedAt;
            sb.append("RUNNING (phase ")
                    .append(s.getStatus() == null ? "?" : s.getStatus())
                    .append(", ")
                    .append(elapsed / 1000)
                    .append("s elapsed)");
        } else if (s.getStatus() == HactarStatus.DONE) {
            sb.append("finished (").append(s.getExecutionDurationMs()).append("ms)");
        } else if (s.getStatus() == HactarStatus.FAILED) {
            sb.append("failed");
        } else if (s.getStatus() == HactarStatus.READY) {
            sb.append("no run yet (kick with hactar_start)");
        } else {
            sb.append("idle (interrupted mid-run — restart with hactar_start)");
        }
        sb.append('\n');
        sb.append("script: ")
                .append(
                        s.getScriptRef() == null
                                ? "(not set — ask the user which script document to run)"
                                : s.getScriptRef())
                .append('\n');
        sb.append("validateBeforeRun: ").append(s.isValidateBeforeRun()).append('\n');
        if (s.getExecutionResult() != null) {
            sb.append("last result: ")
                    .append(preview(renderValue(s.getExecutionResult()), RESULT_PREVIEW_CHARS))
                    .append('\n');
        }
        if (s.getFailureReason() != null) {
            sb.append("last failure: ")
                    .append(preview(s.getFailureReason(), RESULT_PREVIEW_CHARS))
                    .append(
                            s.getExecutionErrorClass() == null
                                    ? ""
                                    : " (errorClass=" + s.getExecutionErrorClass() + ")")
                    .append('\n');
        }
        List<HactarProgressRing.Entry> tail = progressRing.tail(processId, PROGRESS_TAIL);
        if (!tail.isEmpty()) {
            sb.append("progress notes:\n");
            for (HactarProgressRing.Entry e : tail) {
                sb.append("  - ").append(e.text()).append('\n');
            }
        }
        // Live console (Live-Fund 5): while the run is live, quote the
        // in-memory line ring — the persisted consoleTail only exists at
        // the terminal. After the terminal the ring still holds the last
        // run's lines (cleared on the next kick), so the fallback chain
        // covers both phases.
        String liveConsole = consoleLog.renderTail(processId, 5);
        if (!liveConsole.isEmpty()) {
            sb.append("console output (live, stamped with arrival times — re-rendered every "
                    + "turn, quote THIS, not your earlier replies):\n");
            for (String line : liveConsole.split("\n", -1)) {
                sb.append("  ").append(line).append('\n');
            }
        } else {
            String console = ConsoleExcerpt.of(s.getConsoleTail(), 5, 800);
            if (!console.isEmpty()) {
                sb.append("console output (last lines):\n");
                for (String line : console.split("\n", -1)) {
                    sb.append("  ").append(line).append('\n');
                }
            }
        }
        sb.append("\nUser controls: hactar_start (scriptRef + options — a new start requires the "
                + "previous run stopped), hactar_stop (halt your body). Changes to your own code "
                + "go through a Slart mode=Update spawn (current path + request → new version of "
                + "you) — you cannot rewrite yourself.");
        return sb.toString();
    }

    private String renderValue(Object value) {
        if (value == null) return "(no return value)";
        if (value instanceof String str) return str;
        try {
            return "```json\n" + objectMapper.writeValueAsString(value) + "\n```";
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }
}
