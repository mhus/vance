package de.mhus.vance.addon.brain.nutrimat.redbull;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat;
import de.mhus.vance.addon.brain.nutrimat.NutrimatExhaustedException;
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
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code redbull} — the hard-budget loop: exhaustion is an
 * <em>error</em>, nothing else. No judge, no continuation, no best-free-text
 * rescue: when the iteration budget runs out, the loop throws
 * {@link NutrimatExhaustedException} and the turn ends as a visible failure
 * (a worker closes {@code INCOMPLETE}; a primary parks {@code BLOCKED} with
 * the failure report as its reply).
 *
 * <p>Exactly one axis differs from {@code janx}: what happens at exhaustion.
 * Everything else stays put, so a {@code janx}-vs-{@code redbull} comparison
 * measures that and nothing else. The reactivated shape of the old Ford
 * "exhausted state" — which was an exception, not a state.
 *
 * <p>Named after the drink that gets you going and then lets you drop —
 * the loop that reports the crash instead of softening it.
 */
@Component
public class NutrimatRedbull extends AbstractNutrimat {

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
            ShootyGuardService guardService) {
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
     * The exhausted policy of this nature, in its historical shape: an
     * <em>exception</em>. The turn shell maps it onto a visible failure; the
     * partial work stays in the loop where it died.
     */
    @Override
    protected ExhaustionDecision onExhausted(LoopState state) {
        throw new NutrimatExhaustedException("exhausted — the loop hit its hard limit of " + state.maxIterations()
                + " processing steps (maxIterations) after " + state.iterationsConsumed()
                + " iterations and produced no answer. Hard stop by design: no continuation, "
                + "no partial-work rescue. Start a fresh worker with a tighter scope or a "
                + "higher step limit.");
    }

    /** No best-free-text rescue here either — the failure surfaces verbatim. */
    @Override
    protected ExhaustionDecision onLlmFailure(LoopState state, RuntimeException error) {
        return ExhaustionDecision.hardError("hard stop — the LLM call failed mid-loop (" + error.getMessage()
                + ") and this loop does not rescue partial work.");
    }
}
