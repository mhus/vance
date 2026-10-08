package de.mhus.vance.addon.brain.nutrimat.espresso;

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
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code espresso} — plan first, then execute. Hypothesis: an
 * up-front plan helps on multi-step tasks and only costs on short ones.
 *
 * <p>The loop: round 1 has no tools and must produce a short numbered plan.
 * The plan stays in the context (as the model's own message) and is
 * published through {@link #report}. From round 2 on the model executes the
 * plan in a natural-stop tool loop: every message starts with
 * {@code STEP <n>:} (narrated live), a changed plan is announced with
 * {@code PLAN REVISED:} (published again), and the first message without a
 * tool call is the reply.
 *
 * <p>Nets: an empty reply or a failed model call ends the turn as a visible
 * failure; {@code params.maxIterations} is an opt-in round cap (default
 * off) that carries the best text out as partial work. The interrupt comes
 * from {@link #round}. An answer leaves the process IDLE in both modes.
 */
@Component
public class NutrimatEspresso extends AbstractNutrimat {

    /** Recipe param: opt-in round cap; absent / {@code 0} = no cap. */
    static final String PARAM_MAX_ITERATIONS = "maxIterations";

    static final String PLAN_PROTOCOL = "PLAN FIRST: this round has no tools. Reply with a short numbered plan "
            + "of the steps you will take — nothing else.";

    static final String EXEC_PROTOCOL = "Now execute your plan step by step. Start every message with "
            + "`STEP <n>:`. If the plan must change, write `PLAN REVISED:` followed by the new plan. When all "
            + "steps are done, reply without a tool call — that text is your answer.";

    private static final Pattern STEP = Pattern.compile("(?im)^\\s*\\**\\s*STEP\\s+(\\d+)");

    private static final Pattern PLAN_REVISED = Pattern.compile("(?is)PLAN REVISED:\\s*(.+)");

    public NutrimatEspresso(
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
        return "espresso";
    }

    @Override
    protected String loopType() {
        return "plan first — a tool-less planning round, then step-by-step execution against the plan";
    }

    /** The opt-in round cap — {@code 0} (the default) means no cap. */
    @Override
    protected int iterationBudget(ThinkProcessDocument process) {
        return Math.max(0, paramInt(process, PARAM_MAX_ITERATIONS, 0));
    }

    @Override
    protected TurnOutcome runLoop(
            ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
        // ── Phase 1: the plan — one round, no tools.
        narrateRound(ctx, process, "round 1 — planning (no tools)");
        in.messages().add(SystemMessage.from(PLAN_PROTOCOL));
        AiMessage planReply;
        try {
            planReply = round(process, ctx, in.withToolSpecs(List.of()));
        } catch (NutrimatInterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            return TurnOutcome.failed("The LLM call failed in the planning round: " + messageOf(e));
        }
        stats.iterationsConsumed = 1;
        String plan = planReply.text() == null ? "" : planReply.text().strip();
        if (plan.isBlank()) {
            return TurnOutcome.failed("The model returned an empty plan (no text in the planning round).");
        }
        // Only the plan text enters the context — a tool call the model
        // produced anyway would have no result to pair with.
        in.messages().add(AiMessage.from(plan));
        appendInterimRoundText(ctx, process, plan);
        report(process, "plan: " + plan);
        narrate(ctx, process, "plan made — executing");

        // ── Phase 2: execution — the natural-stop tool loop against the plan.
        in.messages().add(SystemMessage.from(EXEC_PROTOCOL));
        int maxIterations = iterationBudget(process);
        String bestFreeText = "";
        for (int iter = 1; ; iter++) {
            if (maxIterations > 0 && iter >= maxIterations) {
                narrate(ctx, process, "round cap reached after " + iter + " rounds (maxIterations)");
                return TurnOutcome.recovered(
                        bestFreeText.isBlank()
                                ? "The run reached its limit of " + maxIterations
                                        + " rounds (maxIterations) without producing an answer."
                                : bestFreeText);
            }
            AiMessage reply;
            try {
                reply = round(process, ctx, in);
            } catch (NutrimatInterruptedException e) {
                throw e;
            } catch (RuntimeException e) {
                return TurnOutcome.failed("The LLM call failed mid-loop: " + messageOf(e));
            }
            stats.iterationsConsumed = iter + 1;
            String text = reply.text() == null ? "" : reply.text();
            if (text.length() > bestFreeText.length()) {
                bestFreeText = text;
            }
            Integer step = stepOf(text);
            narrateRound(ctx, process, "round " + (iter + 1) + (step == null ? "" : " — step " + step));
            String revised = revisedPlanOf(text);
            if (revised != null) {
                report(process, "plan revised: " + revised);
                narrate(ctx, process, "plan revised");
            }

            if (!reply.hasToolExecutionRequests()) {
                stats.stopCandidates++;
                if (text.isBlank()) {
                    return TurnOutcome.failed("The model returned an empty response (no text, no tool call).");
                }
                narrate(ctx, process, "stop: the model's text is the reply");
                return TurnOutcome.terminal(text, false);
            }
            appendInterimRoundText(ctx, process, text);
            dispatchTools(process, in, reply);
        }
    }

    /** The step number a message announces ({@code STEP <n>:}), or {@code null}. */
    static @Nullable Integer stepOf(@Nullable String text) {
        if (text == null) return null;
        Matcher m = STEP.matcher(text);
        if (!m.find()) return null;
        try {
            return Integer.valueOf(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The revised plan a message announces ({@code PLAN REVISED: …}), or {@code null}. */
    static @Nullable String revisedPlanOf(@Nullable String text) {
        if (text == null) return null;
        Matcher m = PLAN_REVISED.matcher(text);
        if (!m.find()) return null;
        String plan = m.group(1).strip();
        return plan.isEmpty() ? null : plan;
    }

    private static String messageOf(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }
}
