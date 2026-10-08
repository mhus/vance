package de.mhus.vance.addon.brain.nutrimat.salitos;

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
import dev.langchain4j.data.message.UserMessage;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code salitos} — the stop is a decision, and the loop makes it
 * itself. Endless by design: no round cap, no decision cap. Every time the
 * model stops calling tools, its message must be one JSON object —
 * {@code {"done": true|false, "reason": "…", "answer": "…"}} (the protocol is
 * a turn-local system instruction). {@code done=false} keeps the loop going
 * with the model's own reason as the nudge; {@code done=true} ends the turn,
 * {@code answer} is the reply. Every decision is published through the
 * report channel (recorded in the loop state, notified to the client).
 *
 * <p>A reply that is not the JSON object gets a format correction; after
 * {@value #MAX_FORMAT_CORRECTIONS} corrections the raw text is accepted as
 * the reply (a model that cannot speak the protocol must still end). A
 * failed model call and an empty reply end the turn as a failure. No other
 * nets — the interrupt comes from {@code round()}. An answer leaves the
 * process IDLE in both modes.
 */
@Component
@Slf4j
public class NutrimatSalitos extends AbstractNutrimat {

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

    /** Format corrections before the raw text is accepted as the reply. */
    static final int MAX_FORMAT_CORRECTIONS = 2;

    /** The loop protocol — a turn-local system instruction, never persisted. */
    static final String PROTOCOL = "LOOP PROTOCOL (salitos): you decide yourself when the work is done. "
            + "Whenever you stop calling tools, your message must be exactly one JSON object and nothing else:\n"
            + "{\"done\": true|false, \"reason\": \"<why you are done, or what is still missing>\", "
            + "\"answer\": \"<your complete answer to the user — required when done is true>\"}\n"
            + "With done=false you keep working: continue with tool calls on what your reason says is missing.";

    static final String FORMAT_CORRECTION = "FORMAT: your last message was not the required JSON object. "
            + "Reply with exactly {\"done\": true|false, \"reason\": \"…\", \"answer\": \"…\"} "
            + "(answer required when done is true) — or call a tool to keep working.";

    @Override
    protected String natureId() {
        return "salitos";
    }

    @Override
    protected String loopType() {
        return "stop-as-a-decision — the loop itself answers done/continue (JSON) at every stop, endless by design";
    }

    @Override
    protected TurnOutcome runLoop(
            ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
        in.messages().add(SystemMessage.from(PROTOCOL));
        int formatCorrections = 0;
        for (int iter = 0; ; iter++) {
            narrateRound(ctx, process, "round " + (iter + 1));
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
            if (reply.hasToolExecutionRequests()) {
                appendInterimRoundText(ctx, process, text);
                dispatchTools(process, in, reply);
                continue;
            }
            stats.stopCandidates++;
            if (text.isBlank()) {
                return TurnOutcome.failed("The model returned an empty response (no text, no tool call).");
            }
            Decision decision = decisionOf(text);
            if (decision == null) {
                if (formatCorrections >= MAX_FORMAT_CORRECTIONS) {
                    narrate(ctx, process, "format fallback — the raw text is the reply");
                    return TurnOutcome.terminal(text, false);
                }
                formatCorrections++;
                appendInterimRoundText(ctx, process, text);
                narrate(ctx, process, "stop decision: correct — not the JSON decision");
                in.messages().add(reply);
                in.messages().add(SystemMessage.from(FORMAT_CORRECTION));
                continue;
            }
            report(process, "decision: " + (decision.done() ? "done" : "continue") + " — " + decision.reason());
            if (decision.done()) {
                narrate(ctx, process, "stop decision: done — " + decision.reason());
                return TurnOutcome.terminal(decision.answer(), false);
            }
            // The decision is intermediate working state, never the reply.
            appendInterimRoundText(ctx, process, text);
            narrate(ctx, process, "stop decision: continue — " + decision.reason());
            in.messages().add(reply);
            in.messages()
                    .add(UserMessage.from("Continue working. You said what is still missing: " + decision.reason()));
        }
    }

    /** The model's own decision — {@code null} when the reply is not the protocol object. */
    record Decision(boolean done, String reason, String answer) {}

    @Nullable
    Decision decisionOf(String text) {
        Map<String, Object> json = jsonObjectOf(text);
        if (json == null || !(json.get("done") instanceof Boolean done)) return null;
        String reason = json.get("reason") instanceof String r && !r.isBlank() ? r.strip() : "(no reason given)";
        String answer = json.get("answer") instanceof String a ? a.strip() : "";
        if (done && answer.isEmpty()) return null; // done without an answer is a format error
        return new Decision(done, reason, answer);
    }
}
