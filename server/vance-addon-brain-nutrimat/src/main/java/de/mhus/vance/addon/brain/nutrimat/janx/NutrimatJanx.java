package de.mhus.vance.addon.brain.nutrimat.janx;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat;
import de.mhus.vance.addon.brain.nutrimat.NutrimatInterruptedException;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.context.PromptDateContextResolver;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.guard.ShootyGuardService;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.notification.NotificationService;
import de.mhus.vance.brain.prak.HistoryStrengthFilter;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.ClientTurnContextResolver;
import de.mhus.vance.brain.prompt.ScratchpadPromptContributor;
import de.mhus.vance.brain.skill.SkillPromptComposer;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillTriggerMatcher;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code janx} — the reference implementation of the Nutrimat lab:
 * the Ford worker loop (state 2026-10, ford-engine.md §4/§4a), copied — not
 * linked — and owning its loop entirely ({@link #runLoop} overridden; the
 * base kernel's hooks, budget and wallclock net are not used).
 *
 * <p>The loop: the model calls tools as long as it wants; the first message
 * without a tool call <em>is</em> the reply. No routine round cap. The nets
 * ({@link JanxSafetyNet}) measure being stuck, never volume: idle-stuck
 * (same batch 5× — status polls exempt and throttled), empty reply, model
 * failure, per-turn wallclock 60 min ({@code params.maxWallclockMinutes});
 * {@code params.maxIterations} only as an opt-in hard cap. Interrupts come
 * from {@link #round}. Optional {@code params.validation}: the data-relay
 * correction (big tool data, thin reply → correct up to twice).
 *
 * <p>Exits, two modes from the role: an answer leaves the process IDLE in
 * both modes. A safety stop ends a <b>worker</b> with a "⚠️ TASK FAILED"
 * reply and closes it INCOMPLETE (a BLOCKED worker reads as a question to
 * every orchestrator), and parks a <b>chat</b> BLOCKED with a continuable
 * stop text.
 */
@Component
@Slf4j
public class NutrimatJanx extends AbstractNutrimat {

    public NutrimatJanx(
            ThinkProcessService thinkProcessService,
            ObjectMapper objectMapper,
            StreamingProperties streamingProperties,
            ModelCatalog modelCatalog,
            LlmCallTracker llmCallTracker,
            MemoryContextLoader memoryContextLoader,
            EnginePromptResolver enginePromptResolver,
            SystemPromptComposer composer,
            EngineChatFactory engineChatFactory,
            MemoryService memoryService,
            MemoryCompactionService memoryCompactionService,
            SkillResolver skillResolver,
            SkillPromptComposer skillPromptComposer,
            SkillTriggerMatcher skillTriggerMatcher,
            SessionService sessionService,
            PromptDateContextResolver promptDateContextResolver,
            ScratchpadPromptContributor scratchpadPromptContributor,
            WorkspaceService workspaceService,
            HistoryStrengthFilter historyStrengthFilter,
            ClientTurnContextResolver clientTurnContextResolver,
            TurnContextHandlerRegistry turnContextHandlers,
            ShootyGuardService guardService,
            NotificationService notifications) {
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
                skillResolver,
                skillPromptComposer,
                skillTriggerMatcher,
                sessionService,
                promptDateContextResolver,
                scratchpadPromptContributor,
                workspaceService,
                historyStrengthFilter,
                clientTurnContextResolver,
                turnContextHandlers,
                guardService,
                notifications);
    }

    @Override
    protected String natureId() {
        return "janx";
    }

    @Override
    protected String loopType() {
        return "the Ford worker loop — natural stop, safety nets instead of a round cap (reference)";
    }

    /** Validation: tool data (chars) above which the reply is expected to reflect it. */
    private static final int TOOL_DATA_THRESHOLD = 500;

    /** Validation: reply size (chars) below which the data was likely not relayed. */
    private static final int REPLY_BRIEF_THRESHOLD = 200;

    private static final int MAX_VALIDATION_CORRECTIONS = 2;

    private static final String DATA_RELAY_CORRECTION_TEMPLATE =
            "VALIDATION CHECK: tools returned %d chars, your reply has %d — paste the actual data into the reply text.";

    /** The Ford worker loop — see the class doc. */
    @Override
    protected TurnOutcome runLoop(
            ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
        JanxSafetyNet net = new JanxSafetyNet(process, () -> haltRequested(process));
        String bestFreeText = "";
        int toolDataChars = 0;
        int corrections = 0;
        for (int iter = 0; ; iter++) {
            narrateRound(ctx, process, "round " + (iter + 1));
            JanxSafetyNet.Stop beforeRound = net.beforeRound(iter, bestFreeText);
            if (beforeRound != null) {
                return safetyStop(ctx, process, beforeRound);
            }
            AiMessage reply;
            try {
                reply = round(process, ctx, in);
            } catch (NutrimatInterruptedException e) {
                throw e;
            } catch (RuntimeException e) {
                String message = e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
                return safetyStop(
                        ctx, process, new JanxSafetyNet.Stop(JanxSafetyNet.Reason.LLM_FAILURE, message, bestFreeText));
            }
            stats.iterationsConsumed = iter + 1;
            String text = reply.text() == null ? "" : reply.text();
            if (text.length() > bestFreeText.length()) {
                bestFreeText = text;
            }

            if (!reply.hasToolExecutionRequests()) {
                stats.stopCandidates++;
                if (text.isBlank()) {
                    return safetyStop(
                            ctx,
                            process,
                            new JanxSafetyNet.Stop(JanxSafetyNet.Reason.EMPTY_REPLY, "empty response", bestFreeText));
                }
                if (in.validation()
                        && corrections < MAX_VALIDATION_CORRECTIONS
                        && toolDataChars >= TOOL_DATA_THRESHOLD
                        && text.length() <= REPLY_BRIEF_THRESHOLD) {
                    String template = process.getDataRelayCorrectionOverride() == null
                                    || process.getDataRelayCorrectionOverride().isBlank()
                            ? DATA_RELAY_CORRECTION_TEMPLATE
                            : process.getDataRelayCorrectionOverride();
                    String correction = formatOrVerbatim(template, toolDataChars, text.length());
                    // The thin draft is intermediate working state, never the reply.
                    appendInterimRoundText(ctx, process, text);
                    narrate(ctx, process, "stop decision: correct — " + correction);
                    in.messages().add(reply);
                    in.messages().add(SystemMessage.from(correction));
                    corrections++;
                    continue;
                }
                narrate(ctx, process, "stop decision: accept — the model's text is the reply");
                // An answer is "done, ready for the next message" — IDLE in
                // both modes; BLOCKED is reserved for a real obstacle.
                return TurnOutcome.terminal(text, false);
            }

            List<ToolExecutionRequest> calls = reply.toolExecutionRequests();
            JanxSafetyNet.Stop stuck = net.onToolBatch(calls, bestFreeText);
            if (stuck != null) {
                return safetyStop(ctx, process, stuck);
            }
            // Tool calls present — the round is intermediate by definition.
            appendInterimRoundText(ctx, process, text);
            toolDataChars += dispatchTools(process, in, reply);
            net.afterToolBatch(calls);
        }
    }

    /**
     * A net ended the turn. {@code failed} is the shell's hard-failure
     * outcome with the text taken verbatim: a worker closes INCOMPLETE, a
     * chat parks BLOCKED — the stop text says which, and how to go on.
     */
    private TurnOutcome safetyStop(ThinkEngineContext ctx, ThinkProcessDocument process, JanxSafetyNet.Stop stop) {
        boolean worker = process.getParentProcessId() != null;
        narrate(ctx, process, "safety net: " + stop.describe());
        log.warn(
                "Nutrimat[janx] id='{}' safety net '{}' ended the turn ({}) — {}",
                process.getId(),
                stop.reason(),
                stop.detail(),
                worker ? "worker closes INCOMPLETE" : "chat parks BLOCKED");
        return TurnOutcome.failed(worker ? stop.workerText() : stop.continuableText());
    }

    private static String formatOrVerbatim(String template, Object... args) {
        try {
            return String.format(template, args);
        } catch (RuntimeException e) {
            return template;
        }
    }
}
