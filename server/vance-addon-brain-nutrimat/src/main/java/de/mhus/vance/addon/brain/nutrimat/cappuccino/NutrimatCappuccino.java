package de.mhus.vance.addon.brain.nutrimat.cappuccino;

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
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code cappuccino} — the hard budget made visible. The same loop
 * as {@code redbull} (tools as long as the model wants, the first message
 * without a tool call is the reply, at most {@code params.maxIterations}
 * rounds, exhaustion raises {@link NutrimatExhaustedException}), with one
 * difference: before every round the model sees its budget — "round 7 of
 * 20 (13 left), 4 min elapsed" — and the last round is told to answer now.
 * The question this nature asks: does a visible budget change the
 * behaviour (earlier answers, fewer exhausted turns) against redbull at the
 * same limit?
 *
 * <p>The budget note is transient: appended right before the model call,
 * removed right after it, so it never piles up in the turn's messages. No
 * continue-gate (that is redbull's own), no other nets. The interrupt comes
 * from {@code round()}. An answer leaves the process IDLE in both modes.
 */
@Component
public class NutrimatCappuccino extends AbstractNutrimat {

    /** Hard round cap when neither recipe nor runtime override sets one. */
    static final int DEFAULT_HARD_LIMIT = 20;

    public NutrimatCappuccino(
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
        return "cappuccino";
    }

    @Override
    protected String loopType() {
        return "hard budget made visible — every round sees its remaining rounds and elapsed time";
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
        Instant start = Instant.now();
        for (int iter = 0; ; iter++) {
            narrateRound(ctx, process, "round " + (iter + 1) + "/" + budget);
            if (iter >= budget) {
                narrate(ctx, process, "budget exhausted after " + iter + " rounds — hard stop");
                throw new NutrimatExhaustedException("exhausted — the loop hit its hard limit of " + budget
                        + " processing steps (maxIterations) and produced no answer, although it saw its "
                        + "budget every round. Hard stop by design: no continuation, no partial-work rescue. "
                        + "Start a fresh worker with a tighter scope or a higher step limit.");
            }
            long elapsedMinutes = Duration.between(start, Instant.now()).toMinutes();
            ChatMessage note = SystemMessage.from(budgetNote(iter, budget, elapsedMinutes));
            AiMessage reply;
            in.messages().add(note);
            try {
                reply = round(process, ctx, in);
            } catch (NutrimatInterruptedException e) {
                throw e;
            } catch (RuntimeException e) {
                return TurnOutcome.failed("⚠️ TASK FAILED — hard stop — the LLM call failed mid-loop (" + e.getMessage()
                        + ") and this loop does not rescue partial work.");
            } finally {
                removeByIdentity(in.messages(), note);
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

    /**
     * The budget the model sees before round {@code iteration} (0-based):
     * position, rounds left, elapsed minutes; the last round is told to
     * answer without tools.
     */
    static String budgetNote(int iteration, int budget, long elapsedMinutes) {
        int round = iteration + 1;
        int left = budget - round;
        String head = "BUDGET: round " + round + " of " + budget + " (" + left + " left), " + elapsedMinutes
                + " min elapsed.";
        if (left <= 0) {
            return head + " LAST ROUND: answer now without calling tools — a tool call in this round "
                    + "ends the run as exhausted.";
        }
        return head + " Plan your work so the answer comes before the budget runs out; when the budget is "
                + "exhausted the run fails without an answer.";
    }

    /** Removes exactly {@code message} (identity, not equals) — the transient budget note. */
    static void removeByIdentity(List<ChatMessage> messages, ChatMessage message) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) == message) {
                messages.remove(i);
                return;
            }
        }
    }
}
