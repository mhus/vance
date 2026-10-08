package de.mhus.vance.addon.brain.nutrimat.absint;

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
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import dev.langchain4j.data.message.AiMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code absint} — the stop with the mandatory self-accounting: at every
 * natural-stop candidate a schema-bound LightLlm call must produce a non-empty
 * report of what this loop did. The agent decides nothing — whether to keep
 * running is {@code salitos}' axis, not this one — the stop is accepted as the
 * reply, but it never goes unaccounted: the report is appended to the persisted
 * loop state ({@code nutrimatState.roundReports}, like the turn counter) and
 * pushed to the client as a notification.
 *
 * <p>Exactly one axis differs from {@code salitos}: both fire one cheap LLM
 * call at every natural stop, but the call reports instead of deciding. The
 * comparison {@code salitos} vs. {@code absint} measures exactly that framing
 * — verdict vs. account. The loop mechanics, the budget shape and the stop
 * semantics (accept, no decision budget) are identical to {@code janx}'s.
 *
 * <p>The report is never blank by contract: a judge call that fails or comes
 * back empty degrades to the model's draft text, and a blank draft degrades to
 * a fixed fallback sentence — the accounting obligation survives the judge.
 *
 * <p>Absinth — the drink you have to account for the next morning.
 */
@Component
public class NutrimatAbsint extends AbstractNutrimat {

    /**
     * Cap on the round-note snippet — the note is a dimmed progress line,
     * the model's full text lives in the working log (interim messages).
     */
    private static final int NARRATION_SNIPPET_LIMIT = 160;

    private final NutrimatJudge judge;

    public NutrimatAbsint(
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
        return "absint";
    }

    @Override
    protected String loopType() {
        return "stop with a mandatory round report — every natural stop is accounted for, recorded and notified";
    }

    /**
     * The round note carries the model's own words: what it last said it is
     * doing is the live story of this loop — a bare counter (or a budget
     * figure, which this axis does not have) would say nothing. Text-less
     * rounds fall back to the plain counter.
     */
    @Override
    protected String roundNarration(LoopState state, String lastRoundText) {
        String round = "round " + (state.iterationsConsumed() + 1);
        if (lastRoundText == null || lastRoundText.isBlank()) {
            return round;
        }
        String text = lastRoundText.strip();
        return round + ": "
                + (text.length() > NARRATION_SNIPPET_LIMIT ? text.substring(0, NARRATION_SNIPPET_LIMIT) + "…" : text);
    }

    /**
     * The reporting point of this nature: the model stopped calling tools, and
     * instead of deciding anything (janx accepts silently, salitos judges), the
     * loop demands an account of what this round did. The nature sends the
     * account through the report channel ({@link #report} — recorded in the
     * loop state, notified to the client); the stop is accepted as the reply
     * either way.
     */
    @Override
    protected StopDecision onNaturalStopCandidate(LoopState state, AiMessage reply) {
        String draft = reply.text() == null ? "" : reply.text();
        NutrimatJudge.RoundReport roundReport =
                judge.reportRound(state.process(), state.userGoal(), draft, state.iterationsConsumed());
        // The nature owns the accounting timing — here: an account is due at
        // every stop. The base records it into the loop state and notifies the
        // client; the decision stays pure.
        report(state, roundReport.report());
        return StopDecision.accept();
    }
}
