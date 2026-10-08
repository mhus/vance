package de.mhus.vance.addon.brain.nutrimat.filter;

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
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code filter} — fresh context per round. The model calls tools as
 * long as it wants and the first message without a tool call is the reply,
 * but the working context never grows: every round sees the turn's base
 * (prompt, history, inbox), the loop protocol with the running notes, and
 * the tool exchange of the <em>previous round only</em>. Older tool results
 * are dropped — what the model still needs it must carry forward in its
 * {@code NOTES:} block. The question this nature asks: does a short, fresh
 * context beat a growing history on long tasks and small models?
 *
 * <p>No nets except the opt-in {@code params.maxIterations} (absent / 0 =
 * no cap): reaching it ends the turn with the notes as partial progress.
 * The interrupt comes from {@code round()}. An answer leaves the process
 * IDLE in both modes.
 */
@Component
public class NutrimatFilter extends AbstractNutrimat {

    /** Recipe param: opt-in round cap; absent / {@code 0} = no cap. */
    static final String PARAM_MAX_ITERATIONS = "maxIterations";

    /** Marker that opens the running notes in a working message. */
    static final String NOTES_MARKER = "NOTES:";

    /** The loop protocol — a turn-local system instruction, rebuilt every round, never persisted. */
    static final String PROTOCOL = "LOOP PROTOCOL (filter): your working context is rebuilt every round. "
            + "You see the task, your own running notes and the tool results of your LAST round only — "
            + "older tool results are dropped after one round.\n"
            + "While you are working, every message you write must start with a `NOTES:` block that carries "
            + "forward everything you still need: findings with their concrete values, what is done, what is "
            + "still open. Anything you do not write into NOTES is gone in the next round.\n"
            + "When you are finished, reply without calling a tool — that text is your answer.";

    public NutrimatFilter(
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
        return "filter";
    }

    @Override
    protected String loopType() {
        return "fresh context per round — only goal, running notes and the last round's tool results; "
                + "history does not grow";
    }

    /** The opt-in round cap — {@code params.maxIterations}, {@code 0} = none. */
    @Override
    protected int iterationBudget(ThinkProcessDocument process) {
        return Math.max(0, paramInt(process, PARAM_MAX_ITERATIONS, 0));
    }

    @Override
    protected TurnOutcome runLoop(
            ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
        int cap = iterationBudget(process);
        List<ChatMessage> base = new ArrayList<>(in.messages());
        List<ChatMessage> lastExchange = new ArrayList<>();
        String notes = "";
        for (int iter = 0; ; iter++) {
            narrateRound(ctx, process, "round " + (iter + 1) + " (notes: " + notes.length() + " chars)");
            if (cap > 0 && iter >= cap) {
                narrate(ctx, process, "round cap reached after " + iter + " rounds — ending with the notes");
                return TurnOutcome.recovered(
                        notes.isBlank()
                                ? "The run hit its limit of " + cap + " rounds (maxIterations) without notes "
                                        + "of its progress."
                                : notes);
            }
            rebuildContext(in, base, notes, lastExchange);
            AiMessage reply;
            try {
                reply = round(process, ctx, in);
            } catch (NutrimatInterruptedException e) {
                throw e;
            } catch (RuntimeException e) {
                return TurnOutcome.failed("The LLM call failed mid-loop: " + e.getMessage());
            }
            stats.iterationsConsumed = iter + 1;
            String text = reply.text() == null ? "" : reply.text();
            if (!reply.hasToolExecutionRequests()) {
                stats.stopCandidates++;
                if (text.isBlank()) {
                    return TurnOutcome.failed("The model returned an empty response (no text, no tool call).");
                }
                narrate(ctx, process, "stop: the model's text is the reply");
                return TurnOutcome.terminal(text, false);
            }
            appendInterimRoundText(ctx, process, text);
            String updated = notesOf(text);
            if (updated == null) {
                narrate(ctx, process, "notes not updated — the round carried no text");
            } else {
                notes = updated;
            }
            // Keep only this round's exchange: the assistant message and its
            // tool results — everything dispatchTools appends from here. The
            // previous round's exchange sits before this mark and is dropped.
            int from = in.messages().size();
            dispatchTools(process, in, reply);
            lastExchange =
                    new ArrayList<>(in.messages().subList(from, in.messages().size()));
        }
    }

    /** Resets the working context to base + protocol/notes + the previous round's exchange. */
    static void rebuildContext(LoopInputs in, List<ChatMessage> base, String notes, List<ChatMessage> lastExchange) {
        List<ChatMessage> messages = in.messages();
        messages.clear();
        messages.addAll(base);
        messages.add(
                SystemMessage.from(PROTOCOL + "\n\nYOUR NOTES SO FAR:\n" + (notes.isBlank() ? "(none yet)" : notes)));
        messages.addAll(lastExchange);
    }

    /**
     * The notes a working round carries forward: everything after the
     * {@code NOTES:} marker, else the whole text; {@code null} for a
     * text-less round (the previous notes stay).
     */
    static @Nullable String notesOf(@Nullable String text) {
        if (text == null || text.isBlank()) return null;
        int at = text.indexOf(NOTES_MARKER);
        String notes = at >= 0 ? text.substring(at + NOTES_MARKER.length()) : text;
        notes = notes.strip();
        return notes.isEmpty() ? null : notes;
    }
}
