package de.mhus.vance.addon.brain.nutrimat.affogato;

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
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code affogato} — the external critic. The model calls tools as
 * long as it wants; when it stops, its draft is not yet the reply: a critic
 * ({@link AffogatoCritic}, a LightLlm call) attacks it. The critic sees the
 * user's goal, the draft <b>and</b> the tool trace — what the loop actually
 * did and found — and answers {@code accept} or {@code revise}. A revise
 * sends the critique back into the loop (the model may call tools again);
 * an accept makes the draft the reply. Every verdict goes through the report
 * channel.
 *
 * <p>Hypothesis: an external adversary improves the answer more than
 * salitos' self-decision. {@code params.maxCritiques} (default
 * {@value #DEFAULT_MAX_CRITIQUES}, {@code 0} = unlimited) bounds the critic
 * rounds — once spent, the next draft is accepted without a critique.
 *
 * <p>Nets: opt-in {@code params.maxIterations} (default {@code 0} = none) and
 * the per-turn wallclock {@code params.maxWallclockMinutes} (default
 * {@value #DEFAULT_WALLCLOCK_MINUTES}); both end the turn with the best
 * partial text as a hard-failure outcome. A failed model call and an empty
 * reply end the turn as a failure. The interrupt comes from {@code round()}.
 * An answer leaves the process IDLE in both modes.
 */
@Component
@Slf4j
public class NutrimatAffogato extends AbstractNutrimat {

    static final int DEFAULT_MAX_CRITIQUES = 3;
    static final int DEFAULT_WALLCLOCK_MINUTES = 60;

    /** Per-result cap in the critic's tool trace. */
    static final int TRACE_RESULT_LIMIT = 1500;

    /** Per-call argument cap in the critic's tool trace. */
    static final int TRACE_ARGS_LIMIT = 300;

    /** Cap on the whole tool trace handed to the critic. */
    static final int TRACE_TOTAL_LIMIT = 12000;

    private final AffogatoCritic critic;

    public NutrimatAffogato(
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
            AffogatoCritic critic) {
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
        this.critic = critic;
    }

    @Override
    protected String natureId() {
        return "affogato";
    }

    @Override
    protected String loopType() {
        return "external critic — at every stop a critic who sees the tool work attacks the answer until it accepts";
    }

    /** Opt-in round cap only — {@code 0} (default) means no cap. */
    @Override
    protected int iterationBudget(ThinkProcessDocument process) {
        return paramInt(process, "maxIterations", 0);
    }

    @Override
    protected TurnOutcome runLoop(
            ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
        int maxIterations = iterationBudget(process);
        int maxCritiques = paramInt(process, "maxCritiques", DEFAULT_MAX_CRITIQUES);
        int wallclockMinutes = paramInt(process, "maxWallclockMinutes", DEFAULT_WALLCLOCK_MINUTES);
        long deadlineMs = wallclockMinutes > 0 ? System.currentTimeMillis() + wallclockMinutes * 60_000L : 0;
        String bestFreeText = "";
        int critiques = 0;
        for (int iter = 0; ; iter++) {
            narrateRound(
                    ctx, process, "round " + (iter + 1) + (critiques > 0 ? " (critiques: " + critiques + ")" : ""));
            if (maxIterations > 0 && iter >= maxIterations) {
                narrate(ctx, process, "round cap of " + maxIterations + " reached — ending with the best partial work");
                return TurnOutcome.recovered(nonBlankOr(
                        bestFreeText,
                        "The run hit its round cap of " + maxIterations + " (maxIterations) without an answer."));
            }
            if (deadlineMs > 0 && System.currentTimeMillis() >= deadlineMs) {
                narrate(ctx, process, "wallclock net reached — ending with the best partial work");
                return TurnOutcome.recovered(nonBlankOr(
                        bestFreeText, "The run exceeded its " + wallclockMinutes + "-minute wallclock budget."));
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
            stats.iterationsConsumed = iter + 1;
            String text = reply.text() == null ? "" : reply.text();
            if (text.length() > bestFreeText.length()) {
                bestFreeText = text;
            }
            if (reply.hasToolExecutionRequests()) {
                appendInterimRoundText(ctx, process, text);
                dispatchTools(process, in, reply);
                continue;
            }
            stats.stopCandidates++;
            if (text.isBlank()) {
                return TurnOutcome.failed("The model returned an empty response (no text, no tool call) — no answer.");
            }
            if (maxCritiques > 0 && critiques >= maxCritiques) {
                narrate(ctx, process, "critique budget of " + maxCritiques + " spent — the draft is the reply");
                return TurnOutcome.terminal(text, false);
            }
            AffogatoCritic.Verdict verdict = critic.critique(process, in.userGoal(), text, toolTrace(in.messages()));
            critiques++;
            report(process, "critic: " + (verdict.accept() ? "accept" : "revise") + " — " + verdict.critique());
            if (verdict.accept()) {
                narrate(ctx, process, "critic: accept — the draft is the reply");
                return TurnOutcome.terminal(text, false);
            }
            // The critic pushed back — the draft is working state, never the reply.
            appendInterimRoundText(ctx, process, text);
            narrate(ctx, process, "critic: revise — " + verdict.critique());
            in.messages().add(reply);
            in.messages()
                    .add(UserMessage.from(
                            "CRITIC: " + verdict.critique() + "\nRevise your answer; you may call tools again."));
        }
    }

    /**
     * The tool work of the turn as the critic reads it: every tool call
     * (name + arguments) with its result, in call order. Results are cut at
     * {@value #TRACE_RESULT_LIMIT} chars, arguments at
     * {@value #TRACE_ARGS_LIMIT}. Over the {@value #TRACE_TOTAL_LIMIT}-char
     * total the <b>oldest</b> entries are dropped (with a marker): the draft
     * is built on the latest work, so that is what the critic must see.
     */
    static String toolTrace(List<ChatMessage> messages) {
        Map<String, ToolExecutionRequest> requests = new HashMap<>();
        List<String> entries = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest r : ai.toolExecutionRequests()) {
                    if (r.id() != null) requests.put(r.id(), r);
                }
            } else if (m instanceof ToolExecutionResultMessage result) {
                @Nullable ToolExecutionRequest r = result.id() == null ? null : requests.get(result.id());
                String args = r == null ? "" : cut(r.arguments(), TRACE_ARGS_LIMIT);
                entries.add("- " + result.toolName() + "(" + args + ") → " + cut(result.text(), TRACE_RESULT_LIMIT));
            }
        }
        int total = 0;
        int from = entries.size();
        while (from > 0 && total + entries.get(from - 1).length() + 1 <= TRACE_TOTAL_LIMIT) {
            from--;
            total += entries.get(from).length() + 1;
        }
        StringBuilder sb = new StringBuilder();
        if (from > 0) {
            sb.append("- … ").append(from).append(" earlier tool call(s) omitted\n");
        }
        for (String e : entries.subList(from, entries.size())) {
            sb.append(e).append('\n');
        }
        return sb.toString().strip();
    }

    private static String cut(@Nullable String text, int limit) {
        if (text == null) return "";
        return text.length() > limit ? text.substring(0, limit) + "…" : text;
    }

    private static String nonBlankOr(@Nullable String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate : fallback;
    }
}
