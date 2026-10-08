package de.mhus.vance.addon.brain.nutrimat.clubmate;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat;
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
import de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code clubmate} — the exhausted loop with a judge: when the iteration
 * budget runs out, a single schema-bound LightLlm call decides whether the
 * loop earned a fresh budget ({@code extend}) or whether the answer gets
 * synthesized from what has been gathered ({@code synthesize}).
 *
 * <p>Exactly one axis differs from {@code redbull}: at exhaustion a judge
 * decides instead of the run failing. The loop mechanics, the budget shape
 * and the prompts are identical — a {@code redbull}-vs-{@code clubmate}
 * comparison measures the judge and nothing else. Extensions carry no fixed
 * ceiling (the judge may keep granting while the loop stays healthy); the
 * per-turn wallclock net bounds a runaway judge.
 *
 * <p>Club-Mate keeps the night going — the loop that asks before it drops.
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

    @Override
    protected String natureId() {
        return "clubmate";
    }

    @Override
    protected String loopType() {
        return "exhausted budget with a judge at exhaustion (extend vs. synthesize)";
    }

    /** clubmate's own budget: the judge only speaks at exhaustion, so the
     * cap is deliberately smaller than redbull's — the recipe's
     * {@code params.maxIterations} (override &gt; recipe &gt; this default). */
    @Override
    protected int iterationBudget(ThinkProcessDocument process) {
        return paramInt(process, "maxIterations", DEFAULT_JUDGE_BUDGET);
    }

    /**
     * Budget rounds plus the judge's extensions — the extension marker is
     * the live signal that the judge granted a fresh budget.
     */
    @Override
    protected String roundNarration(LoopState state, String lastRoundText) {
        return "round " + (state.iterationsConsumed() + 1) + "/" + state.iterationBudget()
                + (state.extensions() > 0 ? " (extended " + state.extensions() + "×)" : "");
    }

    /** The judge decides: fresh budget and keep going, or stop with an answer. */
    @Override
    protected ExhaustionDecision onExhausted(LoopState state) {
        NutrimatJudge.ExhaustedJudgment verdict = judge.judgeExhausted(
                state.process(), state.userGoal(), state.bestFreeText(), state.iterationsConsumed());
        if (verdict.extend()) {
            return ExhaustionDecision.extend(verdict.text(), verdict.reason());
        }
        // The judge vouched for the answer — a normal terminal reply, not a
        // hard-failure outcome: the work is considered finished here.
        return ExhaustionDecision.synthesize(verdict.text(), false);
    }
}
