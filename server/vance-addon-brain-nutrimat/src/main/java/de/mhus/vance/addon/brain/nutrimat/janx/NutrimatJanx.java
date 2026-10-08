package de.mhus.vance.addon.brain.nutrimat.janx;

import de.mhus.vance.addon.brain.nutrimat.AbstractNutrimat;
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
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code janx} — the basis loop of the Nutrimat lab: Ford's
 * natural-stop semantics, independently implemented as the baseline every
 * other nature is measured against.
 *
 * <p>The loop: dispatch tool calls as long as the model emits them; the first
 * assistant message without a tool call <em>is</em> the reply. No iteration
 * budget — an uncapped loop runs until the natural stop; the wallclock net is
 * the runaway bound, and its recovery carries the best free text out as a
 * hard-failure outcome (a worker closes {@code INCOMPLETE}); an LLM collapse
 * recovers the same way.
 * One opt-in deviation from a dumb copy: the data-relay-gap validation
 * correction of the Ford baseline rides along via {@code params.validation}.
 *
 * <p>Janx Spirit is what the aliens drank before the Pan Galactic Gargle
 * Blaster — the plain base pour of the lab.
 */
@Component
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
        return "natural-stop tool loop (the Ford baseline)";
    }
}
