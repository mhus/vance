package de.mhus.vance.addon.brain.nutrimat.clubmate;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat;
import de.mhus.vance.addon.brain.nutrimat.NutrimatInterruptedException;
import de.mhus.vance.addon.brain.nutrimat.NutrimatJudge;
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
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code clubmate} — the budget with a judge. Its own loop: the model
 * calls tools as long as it wants, the first message without a tool call is
 * the reply; a budget segment spans {@code params.maxIterations} rounds
 * (default 12). When a segment runs out, a judge (one LightLlm call) looks at
 * the work so far — the longest text the model wrote <em>and</em> the tool
 * calls it made — and decides: a fresh budget with a nudge (extend, no fixed
 * ceiling), or the answer (synthesize, a normal end of the turn).
 *
 * <p>The judge can keep extending, so the loop carries its own wallclock net
 * (30 minutes per turn): past it, extensions are refused and the best
 * partial work ends the turn as a failure. A failed model call and an empty
 * reply end the turn as a failure too. The interrupt comes from
 * {@code round()}. An answer leaves the process IDLE in both modes.
 */
@Component
public class NutrimatClubmate extends AbstractNutrimat {

    /** Round cap when neither recipe nor runtime override sets one — the
     * judge only fires at exhaustion, so the default is deliberately tight. */
    private static final int DEFAULT_JUDGE_BUDGET = 12;

    private final NutrimatJudge judge;

    public NutrimatClubmate(
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
            NotificationService notifications,
            NutrimatJudge judge) {
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
        this.judge = judge;
    }

    /** clubmate's own runaway bound for a judge that keeps extending. */
    private static final long TURN_WALLCLOCK_MINUTES = 30;

    /** How many tool calls the judge sees, newest last — enough to judge, bounded for the call. */
    private static final int JUDGE_TOOL_LOG_LIMIT = 30;

    @Override
    protected String natureId() {
        return "clubmate";
    }

    @Override
    protected String loopType() {
        return "exhausted budget with a judge at exhaustion (extend vs. synthesize)";
    }

    /**
     * clubmate's budget: the judge only speaks at exhaustion, so the cap is
     * deliberately smaller than redbull's — the recipe's
     * {@code params.maxIterations} (override &gt; recipe &gt; this default).
     */
    @Override
    protected int iterationBudget(ThinkProcessDocument process) {
        return paramInt(process, "maxIterations", DEFAULT_JUDGE_BUDGET);
    }

    @Override
    protected TurnOutcome runLoop(
            ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
        int budget = iterationBudget(process);
        long deadlineMs = System.currentTimeMillis() + TURN_WALLCLOCK_MINUTES * 60_000L;
        String bestFreeText = "";
        List<String> toolLog = new ArrayList<>();
        int consumed = 0;
        int inSegment = 0;
        while (true) {
            narrateRound(
                    ctx,
                    process,
                    "round " + (consumed + 1) + "/" + budget
                            + (stats.extensions > 0 ? " (extended " + stats.extensions + "×)" : ""));
            if (inSegment >= budget) {
                narrate(ctx, process, "budget exhausted after " + consumed + " rounds — asking the judge");
                NutrimatJudge.ExhaustedJudgment verdict =
                        judge.judgeExhausted(process, in.userGoal(), gatheredWork(bestFreeText, toolLog), consumed);
                if (verdict.extend() && System.currentTimeMillis() < deadlineMs) {
                    narrate(ctx, process, "judge: extend — " + verdict.reason());
                    in.messages()
                            .add(UserMessage.from(
                                    verdict.text() == null || verdict.text().isBlank()
                                            ? "Continue working toward the goal — you have a fresh budget."
                                            : verdict.text()));
                    stats.extensions++;
                    inSegment = 0;
                    continue;
                }
                if (verdict.extend()) {
                    narrate(ctx, process, "wallclock net reached — refusing further extensions");
                    return TurnOutcome.recovered(
                            bestFreeText.isBlank()
                                    ? "The run exceeded its " + TURN_WALLCLOCK_MINUTES + "-minute wallclock budget."
                                    : bestFreeText);
                }
                // The judge vouched for the answer — a normal end of the turn.
                narrate(ctx, process, "judge: synthesize — " + verdict.reason());
                String answer = verdict.text() == null || verdict.text().isBlank() ? bestFreeText : verdict.text();
                return TurnOutcome.terminal(answer, false);
            }
            AiMessage reply;
            try {
                reply = round(process, ctx, in);
            } catch (NutrimatInterruptedException e) {
                throw e;
            } catch (RuntimeException e) {
                return bestFreeText.isBlank()
                        ? TurnOutcome.failed("The LLM call failed and no partial work is available: " + e.getMessage())
                        : TurnOutcome.recovered(bestFreeText);
            }
            consumed++;
            inSegment++;
            stats.iterationsConsumed = consumed;
            String text = reply.text() == null ? "" : reply.text();
            if (text.length() > bestFreeText.length()) {
                bestFreeText = text;
            }
            if (!reply.hasToolExecutionRequests()) {
                stats.stopCandidates++;
                if (text.isBlank()) {
                    return TurnOutcome.failed(
                            "The model returned an empty response (no text, no tool call) — no answer.");
                }
                narrate(ctx, process, "stop: the model's text is the reply");
                return TurnOutcome.terminal(text, false);
            }
            for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                toolLog.add(call.name() + " " + abbreviate(call.arguments()));
            }
            appendInterimRoundText(ctx, process, text);
            dispatchTools(process, in, reply);
        }
    }

    /**
     * What the judge reads as the work so far: the longest text the model
     * wrote plus the tool calls it made — without the calls the judge would
     * decide "is more work worthwhile" without knowing what was done.
     */
    private static String gatheredWork(String bestFreeText, List<String> toolLog) {
        StringBuilder sb = new StringBuilder(bestFreeText.isBlank() ? "(no text yet)" : bestFreeText);
        if (!toolLog.isEmpty()) {
            sb.append("\n\nTool calls so far (").append(toolLog.size()).append("):");
            int from = Math.max(0, toolLog.size() - JUDGE_TOOL_LOG_LIMIT);
            if (from > 0) sb.append("\n- … ").append(from).append(" earlier call(s)");
            for (String entry : toolLog.subList(from, toolLog.size())) {
                sb.append("\n- ").append(entry);
            }
        }
        return sb.toString();
    }

    private static String abbreviate(String arguments) {
        if (arguments == null || arguments.isBlank()) return "";
        return arguments.length() > 120 ? arguments.substring(0, 120) + "…" : arguments;
    }
}
