package de.mhus.vance.addon.brain.nutrimat.ristretto;

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
 * Nature {@code ristretto} — the janx loop with a mandatory reflection
 * round. Hypothesis: regular reflection prevents getting stuck earlier than
 * janx's idle-stuck net.
 *
 * <p>The loop is the janx loop (natural stop, the first message without a
 * tool call is the reply) with the same nets ({@link RistrettoSafetyNet}, a
 * copy of janx's: idle-stuck, empty reply, model failure, wallclock 60 min,
 * opt-in {@code params.maxIterations}) and the same exits — an answer leaves
 * the process IDLE, a safety stop closes a worker INCOMPLETE and parks a
 * chat BLOCKED. On top: after every {@code params.reflectEvery} tool rounds
 * (default 3 — below the idle-stuck threshold, {@code 0} = off) one round without tools in which the model
 * must reflect — what worked, where it is stuck, the single next step. The
 * reflection stays in the context and is published through {@link #report}.
 * A reflection round counts as a round, never as a tool round. The
 * interrupt comes from {@link #round}.
 */
@Component
@Slf4j
public class NutrimatRistretto extends AbstractNutrimat {

    /** Recipe param: tool rounds between two reflections; {@code 0} = off. */
    static final String PARAM_REFLECT_EVERY = "reflectEvery";

    /**
     * Below the idle-stuck threshold (5) on purpose: a model repeating one
     * batch must meet a reflection before the net fires — otherwise the
     * experiment could never show reflection beating the net.
     */
    static final int DEFAULT_REFLECT_EVERY = 3;

    static final String REFLECT = "REFLECTION — no tools this round: what has worked so far, where are you "
            + "stuck or repeating yourself, what is the single next step?";

    public NutrimatRistretto(
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
        return "ristretto";
    }

    @Override
    protected String loopType() {
        return "the janx loop with a mandatory tool-less reflection round every n rounds";
    }

    /** The janx loop plus the reflection round — see the class doc. */
    @Override
    protected TurnOutcome runLoop(
            ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
        RistrettoSafetyNet net = new RistrettoSafetyNet(process, () -> haltRequested(process));
        int reflectEvery = Math.max(0, paramInt(process, PARAM_REFLECT_EVERY, DEFAULT_REFLECT_EVERY));
        String bestFreeText = "";
        int toolRounds = 0;
        int toolRoundsSinceReflection = 0;
        for (int iter = 0; ; iter++) {
            RistrettoSafetyNet.Stop beforeRound = net.beforeRound(iter, bestFreeText);
            if (beforeRound != null) {
                return safetyStop(ctx, process, beforeRound);
            }

            if (reflectEvery > 0 && toolRoundsSinceReflection >= reflectEvery) {
                // The reflection round: no tools, the model's own account
                // stays in the context for every round that follows.
                narrateRound(ctx, process, "round " + (iter + 1) + " — reflection (no tools)");
                in.messages().add(SystemMessage.from(REFLECT));
                AiMessage reflection;
                try {
                    reflection = round(process, ctx, in.withToolSpecs(List.of()));
                } catch (NutrimatInterruptedException e) {
                    throw e;
                } catch (RuntimeException e) {
                    return safetyStop(
                            ctx,
                            process,
                            new RistrettoSafetyNet.Stop(
                                    RistrettoSafetyNet.Reason.LLM_FAILURE, messageOf(e), bestFreeText));
                }
                stats.iterationsConsumed = iter + 1;
                toolRoundsSinceReflection = 0;
                String text = reflection.text() == null ? "" : reflection.text().strip();
                if (text.isBlank()) {
                    return safetyStop(
                            ctx,
                            process,
                            new RistrettoSafetyNet.Stop(
                                    RistrettoSafetyNet.Reason.EMPTY_REPLY, "empty reflection", bestFreeText));
                }
                // Only the text enters the context — a tool call without a
                // result could not be replayed.
                in.messages().add(AiMessage.from(text));
                appendInterimRoundText(ctx, process, text);
                report(process, "reflection after round " + toolRounds + ": " + text);
                continue;
            }

            narrateRound(ctx, process, "round " + (iter + 1));
            AiMessage reply;
            try {
                reply = round(process, ctx, in);
            } catch (NutrimatInterruptedException e) {
                throw e;
            } catch (RuntimeException e) {
                return safetyStop(
                        ctx,
                        process,
                        new RistrettoSafetyNet.Stop(RistrettoSafetyNet.Reason.LLM_FAILURE, messageOf(e), bestFreeText));
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
                            new RistrettoSafetyNet.Stop(
                                    RistrettoSafetyNet.Reason.EMPTY_REPLY, "empty response", bestFreeText));
                }
                narrate(ctx, process, "stop decision: accept — the model's text is the reply");
                // An answer leaves the process IDLE in both modes.
                return TurnOutcome.terminal(text, false);
            }

            List<ToolExecutionRequest> calls = reply.toolExecutionRequests();
            RistrettoSafetyNet.Stop stuck = net.onToolBatch(calls, bestFreeText);
            if (stuck != null) {
                return safetyStop(ctx, process, stuck);
            }
            appendInterimRoundText(ctx, process, text);
            dispatchTools(process, in, reply);
            net.afterToolBatch(calls);
            toolRounds++;
            toolRoundsSinceReflection++;
        }
    }

    /**
     * A net ended the turn — same exits as janx: a worker closes INCOMPLETE
     * with the "TASK FAILED" text, a chat parks BLOCKED with a continuable
     * stop text.
     */
    private TurnOutcome safetyStop(ThinkEngineContext ctx, ThinkProcessDocument process, RistrettoSafetyNet.Stop stop) {
        boolean worker = process.getParentProcessId() != null;
        narrate(ctx, process, "safety net: " + stop.describe());
        log.warn(
                "Nutrimat[ristretto] id='{}' safety net '{}' ended the turn ({}) — {}",
                process.getId(),
                stop.reason(),
                stop.detail(),
                worker ? "worker closes INCOMPLETE" : "chat parks BLOCKED");
        return TurnOutcome.failed(worker ? stop.workerText() : stop.continuableText());
    }

    private static String messageOf(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }
}
