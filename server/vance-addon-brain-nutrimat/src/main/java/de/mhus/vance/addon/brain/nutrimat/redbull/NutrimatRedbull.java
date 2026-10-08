package de.mhus.vance.addon.brain.nutrimat.redbull;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat;
import de.mhus.vance.addon.brain.nutrimat.NutrimatExhaustedException;
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
import dev.langchain4j.data.message.AiMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code redbull} — the hard budget. Its own loop: the model calls
 * tools as long as it wants, the first message without a tool call is the
 * reply; but the turn has at most {@code params.maxIterations} rounds
 * (default 40). Reaching it is a hard stop by design: the loop raises
 * {@link NutrimatExhaustedException} — a visible failure, no continuation,
 * no partial-work rescue. A failed model call and an empty reply end the
 * turn the same way. On a primary, exhausted ends the process's work: the
 * shell's continue-gate ({@link #exhaustedStopsUntilUserInput}) discards
 * background events until the user speaks.
 *
 * <p>No other nets: the budget bounds the loop. The interrupt comes from
 * {@code round()}. An answer leaves the process IDLE in both modes.
 */
@Component
public class NutrimatRedbull extends AbstractNutrimat {

    /** Hard round cap when neither recipe nor runtime override sets one. */
    private static final int DEFAULT_HARD_LIMIT = 40;

    public NutrimatRedbull(
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
        return "redbull";
    }

    @Override
    protected String loopType() {
        return "hard budget — exhaustion raises the exhausted error (no judge, no rescue)";
    }

    /**
     * redbull's exhausted is a hard stop even on a primary: background events
     * (exec_finished & friends) are discarded after exhaustion — only an
     * explicit user message ("continue") starts the next loop run. Without
     * this gate the BLOCKED auto-wakeup would let stale events bury the
     * failure under follow-up loops (observed live, 2026-10-04).
     */
    @Override
    protected boolean exhaustedStopsUntilUserInput() {
        return true;
    }

    /** The hard round cap — the recipe's {@code params.maxIterations} (override &gt; recipe &gt; default). */
    @Override
    protected int iterationBudget(ThinkProcessDocument process) {
        return paramInt(process, "maxIterations", DEFAULT_HARD_LIMIT);
    }

    @Override
    protected TurnOutcome runLoop(
            ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
        int budget = iterationBudget(process);
        for (int iter = 0; ; iter++) {
            // The budget IS the semantics of this nature — the note counts it.
            narrateRound(ctx, process, "round " + (iter + 1) + "/" + budget);
            if (iter >= budget) {
                narrate(ctx, process, "budget exhausted after " + iter + " rounds — hard stop");
                throw new NutrimatExhaustedException("exhausted — the loop hit its hard limit of " + budget
                        + " processing steps (maxIterations) and produced no answer. Hard stop by design: "
                        + "no continuation, no partial-work rescue. Start a fresh worker with a tighter "
                        + "scope or a higher step limit.");
            }
            AiMessage reply;
            try {
                reply = round(process, ctx, in);
            } catch (NutrimatInterruptedException e) {
                throw e;
            } catch (RuntimeException e) {
                return TurnOutcome.failed("⚠️ TASK FAILED — hard stop — the LLM call failed mid-loop (" + e.getMessage()
                        + ") and this loop does not rescue partial work.");
            }
            stats.iterationsConsumed = iter + 1;
            String text = reply.text() == null ? "" : reply.text();
            if (!reply.hasToolExecutionRequests()) {
                stats.stopCandidates++;
                if (text.isBlank()) {
                    return TurnOutcome.failed("⚠️ TASK FAILED — hard stop — the model returned an empty "
                            + "response (no text, no tool call).");
                }
                narrate(ctx, process, "stop: the model's text is the reply");
                return TurnOutcome.terminal(text, false);
            }
            appendInterimRoundText(ctx, process, text);
            dispatchTools(process, in, reply);
        }
    }
}
