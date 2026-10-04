package de.mhus.vance.addon.brain.nutrimat.salitos;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat;
import de.mhus.vance.addon.brain.nutrimat.NutrimatJudge;
import de.mhus.vance.brain.ai.EngineChatFactory;
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
import de.mhus.vance.brain.skill.SkillPromptComposer;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillTriggerMatcher;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import dev.langchain4j.data.message.AiMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code salitos} — the loop with the "done or keep going?" decision
 * (the Arthur-shaped question, asked of a judge instead of encoded in a typed
 * action): at every natural-stop candidate, a schema-bound LightLlm call
 * judges the draft answer — accept it as the reply, or push the model to
 * continue working.
 *
 * <p>Exactly one axis differs from {@code janx}: the stop is a decision, not
 * a given. The decision budget ({@code params.maxDecisions}, default 3) caps
 * how often the judge may say "continue" per turn — a judge that never sees a
 * finished answer must not spin the turn until the wallclock net; the
 * iteration cap still bounds every round it grants.
 *
 * <p>Salitos — the beer you keep ordering because the evening isn't over yet.
 */
@Component
public class NutrimatSalitos extends AbstractNutrimat {

    /** Judge rounds per turn unless the recipe sets {@code params.maxDecisions}. */
    private static final int DEFAULT_MAX_DECISIONS = 3;

    private final NutrimatJudge judge;

    public NutrimatSalitos(
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
                guardService);
        this.judge = judge;
    }

    @Override
    protected String natureId() {
        return "salitos";
    }

    @Override
    protected String loopType() {
        return "stop-as-a-decision — a judge decides done vs. continue at every natural stop";
    }

    /**
     * The decision point of this nature: the model stopped calling tools, and
     * instead of accepting that as the reply (janx), the judge decides whether
     * the work is actually finished. Once the decision budget is spent, the
     * draft is accepted — the loop ends on an answer, never on a question loop.
     */
    @Override
    protected StopDecision onNaturalStopCandidate(LoopState state, AiMessage reply) {
        int maxDecisions = paramInt(state.process(), "maxDecisions", DEFAULT_MAX_DECISIONS);
        if (state.stopCandidates() > maxDecisions) {
            return StopDecision.accept();
        }
        String draft = reply.text() == null ? "" : reply.text();
        NutrimatJudge.ContinueJudgment verdict =
                judge.judgeContinue(state.process(), state.userGoal(), draft, state.iterationsConsumed());
        if (verdict.done()) {
            return StopDecision.accept();
        }
        return StopDecision.continueLoop(verdict.nudge());
    }
}
