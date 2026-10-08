package de.mhus.vance.addon.brain.nutrimat.absint;

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
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code absint} — every stop is an account, and the loop gives it
 * itself. Its own loop: the model calls tools as long as it wants; when it
 * stops, its message must be one JSON object with a single string field —
 * {@code {"report": "…"}}: what it did in this loop and what came out of it
 * (the protocol is a turn-local system instruction). The report is the
 * reply, and it is recorded in the loop state and notified to the client
 * (the report channel). No done/continue verdict — the stop is accepted.
 *
 * <p>A reply that is not the JSON object gets a format correction; after
 * {@value #MAX_FORMAT_CORRECTIONS} corrections the raw text is taken as the
 * report — the account is never empty. A failed model call and an empty
 * reply end the turn as a failure. The round note carries the model's own
 * words. No other nets — the interrupt comes from {@code round()}. An answer
 * leaves the process IDLE in both modes.
 */
@Component
@Slf4j
public class NutrimatAbsint extends AbstractNutrimat {

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

    /** Format corrections before the raw text is taken as the report. */
    static final int MAX_FORMAT_CORRECTIONS = 2;

    /**
     * Cap on the round-note snippet — the note is a dimmed progress line,
     * the model's full text lives in the working log (interim messages).
     */
    private static final int NARRATION_SNIPPET_LIMIT = 160;

    /** The loop protocol — a turn-local system instruction, never persisted. */
    static final String PROTOCOL = "LOOP PROTOCOL (absint): you must account for your work. "
            + "Whenever you stop calling tools, your message must be exactly one JSON object and nothing else:\n"
            + "{\"report\": \"<what you did in this loop — which tools, what you found — and the result; "
            + "this text is your reply to the user>\"}";

    static final String FORMAT_CORRECTION = "FORMAT: your last message was not the required JSON object. "
            + "Reply with exactly {\"report\": \"<what you did and the result>\"} — or call a tool to keep working.";

    @Override
    protected String natureId() {
        return "absint";
    }

    @Override
    protected String loopType() {
        return "stop with a mandatory account — the loop itself reports (JSON) what it did at every stop";
    }

    @Override
    protected TurnOutcome runLoop(
            ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
        in.messages().add(SystemMessage.from(PROTOCOL));
        int formatCorrections = 0;
        String lastRoundText = "";
        for (int iter = 0; ; iter++) {
            narrateRound(ctx, process, roundNote(iter, lastRoundText));
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
            lastRoundText = text;
            if (reply.hasToolExecutionRequests()) {
                appendInterimRoundText(ctx, process, text);
                dispatchTools(process, in, reply);
                continue;
            }
            stats.stopCandidates++;
            if (text.isBlank()) {
                return TurnOutcome.failed("The model returned an empty response (no text, no tool call).");
            }
            String report = reportOf(text);
            if (report == null) {
                if (formatCorrections < MAX_FORMAT_CORRECTIONS) {
                    formatCorrections++;
                    appendInterimRoundText(ctx, process, text);
                    narrate(ctx, process, "stop decision: correct — not the JSON report");
                    in.messages().add(reply);
                    in.messages().add(SystemMessage.from(FORMAT_CORRECTION));
                    continue;
                }
                narrate(ctx, process, "format fallback — the raw text is the report");
                report = text.strip();
            }
            report(process, report);
            narrate(ctx, process, "stop: the report is the reply");
            return TurnOutcome.terminal(report, false);
        }
    }

    /** The model's report — {@code null} when the reply is not the protocol object. */
    @Nullable
    String reportOf(String text) {
        Map<String, Object> json = jsonObjectOf(text);
        if (json == null || !(json.get("report") instanceof String report) || report.isBlank()) return null;
        return report.strip();
    }

    /**
     * The round note carries the model's own words: what it last said it is
     * doing is the live story of this loop. Text-less rounds fall back to
     * the plain counter.
     */
    static String roundNote(int iteration, @Nullable String lastRoundText) {
        String round = "round " + (iteration + 1);
        if (lastRoundText == null || lastRoundText.isBlank()) {
            return round;
        }
        String text = lastRoundText.strip();
        return round + ": "
                + (text.length() > NARRATION_SNIPPET_LIMIT ? text.substring(0, NARRATION_SNIPPET_LIMIT) + "…" : text);
    }
}
