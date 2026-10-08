package de.mhus.vance.addon.brain.nutrimat.macchiato;

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code macchiato} — two models in one turn. The hypothesis: the
 * quality of a turn comes mostly from the answer (or the plan); the tool
 * work can be done by a small model.
 *
 * <p>Two modes ({@code params.macchiatoMode}):
 * <ul>
 *   <li>{@code worker-first} (default) — a natural-stop tool loop on the
 *       worker model ({@code params.workerModel}); at its stop the worker's
 *       draft goes into the context and the answer model
 *       ({@code params.answerModel}) writes the final answer in one
 *       tool-less round. An answer round that fails or comes back blank
 *       falls back to the worker's draft — the work is done, only its
 *       wording is missing, and a failed turn would hide that.</li>
 *   <li>{@code planner-first} — the answer model writes a numbered plan in
 *       one tool-less round, then the worker model runs the natural-stop
 *       loop along it; the worker's answer is the reply. A blank plan is
 *       narrated and the worker starts without one.</li>
 * </ul>
 * A missing model param leaves that role on the recipe's primary
 * {@code model}. A model spec that does not resolve ends the turn as a
 * visible failure.
 *
 * <p>Nets: an opt-in round cap on the worker ({@code params.maxIterations},
 * default none) and a per-turn wallclock ({@code params.maxWallclockMinutes},
 * default 60) — both carry the best partial work out. An empty worker reply
 * or a failed worker call fails the turn. The interrupt comes from
 * {@code round()}. An answer leaves the process IDLE in both modes.
 */
@Component
@Slf4j
public class NutrimatMacchiato extends AbstractNutrimat {

    static final String PARAM_MODE = "macchiatoMode";
    static final String PARAM_WORKER_MODEL = "workerModel";
    static final String PARAM_ANSWER_MODEL = "answerModel";
    static final String PARAM_MAX_ITERATIONS = "maxIterations";
    static final String PARAM_MAX_WALLCLOCK_MINUTES = "maxWallclockMinutes";

    static final String MODE_WORKER_FIRST = "worker-first";
    static final String MODE_PLANNER_FIRST = "planner-first";

    static final int DEFAULT_WALLCLOCK_MINUTES = 60;

    /** Turn-local instruction for the answer model in worker-first mode. */
    static final String ANSWER_ROUND = "ANSWER ROUND: write the final answer for the user from the work above. "
            + "Use the data the tools returned; do not call tools.";

    /** Turn-local instruction for the planner in planner-first mode. */
    static final String PLAN_ROUND = "PLAN: before any work is done, write a short numbered plan of the steps "
            + "needed to reach the goal. No tools in this round — the plan only.";

    /** Turn-local instruction for the worker after the plan. */
    static final String EXECUTE_PLAN = "EXECUTE: work through the plan above step by step, calling tools as "
            + "needed. When the work is done, reply without a tool call — that text is the answer.";

    public NutrimatMacchiato(
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
        return "macchiato";
    }

    @Override
    protected String loopType() {
        return "two models in one turn — a cheap worker does the tool rounds, a strong model writes the answer "
                + "(or plans)";
    }

    /** The opt-in worker round cap — {@code 0} (default) = none. */
    @Override
    protected int iterationBudget(ThinkProcessDocument process) {
        return paramInt(process, PARAM_MAX_ITERATIONS, 0);
    }

    @Override
    protected TurnOutcome runLoop(
            ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
        String mode = modeOf(process);
        if (!MODE_WORKER_FIRST.equals(mode) && !MODE_PLANNER_FIRST.equals(mode)) {
            narrate(ctx, process, "unknown macchiatoMode '" + mode + "' — using " + MODE_WORKER_FIRST);
            mode = MODE_WORKER_FIRST;
        }
        @Nullable String workerSpec = paramString(process, PARAM_WORKER_MODEL, null);
        @Nullable String answerSpec = paramString(process, PARAM_ANSWER_MODEL, null);
        LoopInputs worker;
        LoopInputs answer;
        try {
            worker = inputsFor(process, ctx, in, workerSpec);
        } catch (NutrimatInterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            return unresolved(process, workerSpec, e);
        }
        try {
            answer = inputsFor(process, ctx, in, answerSpec);
        } catch (NutrimatInterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            return unresolved(process, answerSpec, e);
        }
        Instant start = Instant.now();
        return MODE_PLANNER_FIRST.equals(mode)
                ? plannerFirst(process, ctx, worker, answer, stats, start)
                : workerFirst(process, ctx, worker, answer, stats, start);
    }

    /**
     * The inputs for one role: the primary inputs when {@code modelSpec} is
     * absent, otherwise the same turn driven by that model. Package-private
     * and overridable so the role logic is testable without a live chat
     * factory.
     */
    LoopInputs inputsFor(
            ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, @Nullable String modelSpec) {
        if (modelSpec == null || modelSpec.isBlank()) {
            return in;
        }
        return withModel(process, ctx, in, modelSpec.trim());
    }

    // ──────────────────── Modes ────────────────────

    private TurnOutcome workerFirst(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            LoopInputs worker,
            LoopInputs answer,
            LoopStats stats,
            Instant start) {
        WorkResult work = workLoop(process, ctx, worker, stats, start);
        if (work.stop() != null) {
            return work.stop();
        }
        String draft = work.draft();
        // The worker's draft is working state for the answer model, never
        // the reply itself — log it, then hand it over.
        appendInterimRoundText(ctx, process, draft);
        worker.messages().add(AiMessage.from(draft));
        worker.messages().add(SystemMessage.from(ANSWER_ROUND));
        narrateRound(ctx, process, "answer round · answer " + answer.modelAlias());
        String reply;
        try {
            AiMessage message = round(process, ctx, answer.withToolSpecs(List.of()));
            stats.iterationsConsumed++;
            reply = message.text() == null ? "" : message.text();
        } catch (NutrimatInterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            narrate(ctx, process, "answer model failed (" + messageOf(e) + ") — the worker's draft is the reply");
            reply = "";
        }
        if (reply.isBlank()) {
            narrate(ctx, process, "answer round returned no text — the worker's draft is the reply");
            reply = draft;
        } else {
            narrate(ctx, process, "stop: the answer model's text is the reply");
        }
        report(
                process,
                "worker=" + worker.modelAlias() + " rounds=" + work.rounds() + ", answer=" + answer.modelAlias());
        return TurnOutcome.terminal(reply, false);
    }

    private TurnOutcome plannerFirst(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            LoopInputs worker,
            LoopInputs answer,
            LoopStats stats,
            Instant start) {
        worker.messages().add(SystemMessage.from(PLAN_ROUND));
        narrateRound(ctx, process, "plan round · planner " + answer.modelAlias());
        String plan;
        try {
            AiMessage message = round(process, ctx, answer.withToolSpecs(List.of()));
            stats.iterationsConsumed++;
            plan = message.text() == null ? "" : message.text();
        } catch (NutrimatInterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            return TurnOutcome.failed("The planner model call failed: " + messageOf(e));
        }
        if (plan.isBlank()) {
            narrate(ctx, process, "planner returned no plan — the worker starts without one");
        } else {
            appendInterimRoundText(ctx, process, plan);
            worker.messages().add(AiMessage.from(plan));
            report(process, "plan (" + answer.modelAlias() + "): " + plan.strip());
        }
        worker.messages().add(SystemMessage.from(EXECUTE_PLAN));
        WorkResult work = workLoop(process, ctx, worker, stats, start);
        if (work.stop() != null) {
            return work.stop();
        }
        narrate(ctx, process, "stop: the worker's text is the reply");
        report(
                process,
                "planner=" + answer.modelAlias() + ", worker=" + worker.modelAlias() + " rounds=" + work.rounds());
        return TurnOutcome.terminal(work.draft(), false);
    }

    // ──────────────────── The worker loop ────────────────────

    /**
     * The natural-stop tool loop on the worker model. Ends with the worker's
     * draft (its first message without a tool call) or with a net / failure
     * outcome in {@link WorkResult#stop()}.
     */
    private WorkResult workLoop(
            ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs worker, LoopStats stats, Instant start) {
        int maxIterations = iterationBudget(process);
        int wallclockMinutes = paramInt(process, PARAM_MAX_WALLCLOCK_MINUTES, DEFAULT_WALLCLOCK_MINUTES);
        String bestFreeText = "";
        for (int rounds = 0; ; rounds++) {
            narrateRound(
                    ctx,
                    process,
                    "round " + (rounds + 1) + (maxIterations > 0 ? "/" + maxIterations : "") + " · worker "
                            + worker.modelAlias());
            if (maxIterations > 0 && rounds >= maxIterations) {
                narrate(ctx, process, "round cap reached after " + rounds + " worker rounds — ending with the partial");
                return WorkResult.stopped(
                        TurnOutcome.recovered(partialOr(
                                bestFreeText,
                                "The run hit its limit of " + maxIterations
                                        + " worker rounds (maxIterations) without an answer.")),
                        rounds);
            }
            if (wallclockMinutes > 0 && Duration.between(start, Instant.now()).toMinutes() >= wallclockMinutes) {
                narrate(ctx, process, "wallclock net reached after " + rounds + " worker rounds");
                return WorkResult.stopped(
                        TurnOutcome.recovered(partialOr(
                                bestFreeText,
                                "The run exceeded its " + wallclockMinutes + "-minute wallclock budget.")),
                        rounds);
            }
            AiMessage reply;
            try {
                reply = round(process, ctx, worker);
            } catch (NutrimatInterruptedException e) {
                throw e;
            } catch (RuntimeException e) {
                return WorkResult.stopped(
                        TurnOutcome.failed("The worker model call failed mid-loop: " + messageOf(e)), rounds);
            }
            stats.iterationsConsumed++;
            String text = reply.text() == null ? "" : reply.text();
            if (text.length() > bestFreeText.length()) {
                bestFreeText = text;
            }
            if (!reply.hasToolExecutionRequests()) {
                stats.stopCandidates++;
                if (text.isBlank()) {
                    return WorkResult.stopped(
                            TurnOutcome.failed("The worker model returned an empty response (no text, no tool call)."),
                            rounds + 1);
                }
                return WorkResult.draft(text, rounds + 1);
            }
            appendInterimRoundText(ctx, process, text);
            dispatchTools(process, worker, reply);
        }
    }

    /** What the worker loop produced: a draft, or a stop outcome. */
    private record WorkResult(@Nullable TurnOutcome stop, String draft, int rounds) {

        static WorkResult stopped(TurnOutcome stop, int rounds) {
            return new WorkResult(stop, "", rounds);
        }

        static WorkResult draft(String draft, int rounds) {
            return new WorkResult(null, draft, rounds);
        }
    }

    // ──────────────────── Helpers ────────────────────

    private String modeOf(ThinkProcessDocument process) {
        String raw = paramString(process, PARAM_MODE, MODE_WORKER_FIRST);
        return raw == null ? MODE_WORKER_FIRST : raw.trim().toLowerCase(Locale.ROOT);
    }

    private TurnOutcome unresolved(ThinkProcessDocument process, @Nullable String spec, RuntimeException e) {
        log.warn(
                "Nutrimat[macchiato] id='{}' model '{}' could not be resolved: {}",
                process.getId(),
                spec,
                e.toString());
        return TurnOutcome.failed("model '" + spec + "' could not be resolved: " + messageOf(e));
    }

    private static String partialOr(String partial, String fallback) {
        return partial.isBlank() ? fallback : partial;
    }

    private static String messageOf(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }
}
