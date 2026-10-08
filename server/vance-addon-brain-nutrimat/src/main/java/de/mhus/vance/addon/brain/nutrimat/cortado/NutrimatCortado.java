package de.mhus.vance.addon.brain.nutrimat.cortado;

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
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Nature {@code cortado} — salitos' decision, cut differently: the stop is
 * the loop's own decision, but it is made through a mandatory loop-control
 * tool {@code loop_decide(done, reason, answer)} instead of a JSON message.
 * The tool is the nature's own — added to every round's tool surface and
 * handled by the loop, never dispatched to the tool layer.
 *
 * <p>Hypothesis: the form of the protocol (tool call vs. JSON text) changes
 * the quality of the decision. Endless by design like salitos: no round or
 * decision cap.
 *
 * <ul>
 *   <li>{@code done=true} with an answer, called alone → the answer is the
 *       reply.</li>
 *   <li>{@code done=false} → the tool result tells the model to continue
 *       with its own reason; the loop goes on.</li>
 *   <li>{@code loop_decide} next to other tool calls → rejected ("call it
 *       alone, after your tools have returned"); the other calls run.</li>
 *   <li>{@code done=true} without an answer → rejected; after
 *       {@value #MAX_MISSING_ANSWER} rejections the reason is taken as the
 *       reply (a model that cannot fill the field must still end).</li>
 *   <li>A plain message without a tool call is not accepted: a correction,
 *       and after {@value #MAX_FORMAT_CORRECTIONS} corrections the raw text
 *       is the reply.</li>
 * </ul>
 *
 * <p>Every decision is published through the report channel. A failed model
 * call and an empty reply end the turn as a failure. No other nets — the
 * interrupt comes from {@code round()}. An answer leaves the process IDLE in
 * both modes.
 */
@Component
@Slf4j
public class NutrimatCortado extends AbstractNutrimat {

    /** The loop-control tool's name. */
    static final String LOOP_DECIDE = "loop_decide";

    /** Plain-text corrections before the raw text is accepted as the reply. */
    static final int MAX_FORMAT_CORRECTIONS = 2;

    /** done=true-without-answer rejections before the reason becomes the reply. */
    static final int MAX_MISSING_ANSWER = 2;

    /** The loop-control tool — handled by the loop itself, never dispatched. */
    static final ToolSpecification LOOP_DECIDE_SPEC = ToolSpecification.builder()
            .name(LOOP_DECIDE)
            .description("Decide whether your work is done. Call it ALONE, after your other tools have "
                    + "returned. done=true ends the turn: 'answer' is your complete answer to the user "
                    + "(required). done=false means you keep working: 'reason' says what is still missing.")
            .parameters(JsonObjectSchema.builder()
                    .addBooleanProperty("done", "true when the work is finished, false to keep working")
                    .addStringProperty("reason", "why you are done, or what is still missing")
                    .addStringProperty("answer", "your complete answer to the user — required when done is true")
                    .required("done", "reason")
                    .build())
            .build();

    /** The loop protocol — a turn-local system instruction, never persisted. */
    static final String PROTOCOL = "LOOP PROTOCOL (cortado): you decide yourself when the work is done. "
            + "Call tools as long as you want. To end or to continue you must call the tool `"
            + LOOP_DECIDE + "` — alone, after your other tools have returned: done=true with your complete "
            + "answer ends the turn, done=false with what is still missing keeps you working. "
            + "A plain message without a tool call is not accepted.";

    static final String FORMAT_CORRECTION = "FORMAT: a plain message is not accepted. Call `" + LOOP_DECIDE
            + "` to end (done=true with your answer) or to continue (done=false with what is missing) — "
            + "or call another tool to keep working.";

    static final String MIXED_REJECTION =
            "rejected: call " + LOOP_DECIDE + " alone, after your other tools have returned.";

    static final String ANSWER_REQUIRED = "rejected: 'answer' is required when done=true — call " + LOOP_DECIDE
            + " again with your complete answer to the user.";

    static final String INVALID_ARGUMENTS =
            "rejected: invalid arguments — 'done' (boolean) and 'reason' (string) are required.";

    public NutrimatCortado(
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
        return "cortado";
    }

    @Override
    protected String loopType() {
        return "the stop is the loop's own decision, made through a mandatory loop_decide tool instead of JSON";
    }

    @Override
    protected TurnOutcome runLoop(
            ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
        LoopInputs rounds = in.withToolSpecs(roundSpecs(in.toolSpecs()));
        rounds.messages().add(SystemMessage.from(PROTOCOL));
        int formatCorrections = 0;
        int missingAnswers = 0;
        for (int iter = 0; ; iter++) {
            narrateRound(ctx, process, "round " + (iter + 1));
            AiMessage reply;
            try {
                reply = round(process, ctx, rounds);
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
                if (formatCorrections >= MAX_FORMAT_CORRECTIONS) {
                    narrate(ctx, process, "format fallback — no " + LOOP_DECIDE + " call, the raw text is the reply");
                    return TurnOutcome.terminal(text, false);
                }
                formatCorrections++;
                appendInterimRoundText(ctx, process, text);
                narrate(ctx, process, "stop decision: correct — a plain message, not a " + LOOP_DECIDE + " call");
                rounds.messages().add(reply);
                rounds.messages().add(SystemMessage.from(FORMAT_CORRECTION));
                continue;
            }

            // Tool round — every call gets a result: loop_decide is answered
            // by the loop, every other call is dispatched to the tool layer.
            appendInterimRoundText(ctx, process, text);
            rounds.messages().add(reply);
            List<ToolExecutionRequest> calls = reply.toolExecutionRequests();
            boolean mixed = calls.size() > 1 && calls.stream().anyMatch(c -> LOOP_DECIDE.equals(c.name()));
            @Nullable String answer = null;
            for (ToolExecutionRequest call : calls) {
                String result;
                if (!LOOP_DECIDE.equals(call.name())) {
                    result = invokeTool(process, rounds, call);
                } else if (mixed) {
                    narrate(ctx, process, LOOP_DECIDE + " rejected — called next to other tools");
                    result = MIXED_REJECTION;
                } else {
                    stats.stopCandidates++;
                    Decision decision = decisionOf(call.arguments());
                    if (decision == null) {
                        narrate(ctx, process, LOOP_DECIDE + " rejected — invalid arguments");
                        result = INVALID_ARGUMENTS;
                    } else if (!decision.done()) {
                        report(process, "decision: continue — " + decision.reason());
                        narrate(ctx, process, "stop decision: continue — " + decision.reason());
                        result = "continue: " + decision.reason();
                    } else {
                        report(process, "decision: done — " + decision.reason());
                        if (!decision.answer().isEmpty()) {
                            narrate(ctx, process, "stop decision: done — " + decision.reason());
                            answer = decision.answer();
                            result = "done";
                        } else if (missingAnswers >= MAX_MISSING_ANSWER) {
                            narrate(ctx, process, "answer fallback — done without an answer, the reason is the reply");
                            answer = decision.reason();
                            result = "done";
                        } else {
                            missingAnswers++;
                            narrate(ctx, process, LOOP_DECIDE + " rejected — done without an answer");
                            result = ANSWER_REQUIRED;
                        }
                    }
                }
                rounds.messages().add(ToolExecutionResultMessage.from(call, result));
            }
            if (answer != null) {
                return TurnOutcome.terminal(answer, false);
            }
        }
    }

    /** The round's tool surface: the turn's tools plus {@code loop_decide}. */
    static List<ToolSpecification> roundSpecs(List<ToolSpecification> base) {
        List<ToolSpecification> specs = new ArrayList<>(base);
        specs.add(LOOP_DECIDE_SPEC);
        return specs;
    }

    /** The model's decision from the tool arguments. */
    record Decision(boolean done, String reason, String answer) {}

    /** {@code null} when the arguments do not carry a usable decision. */
    @Nullable
    Decision decisionOf(@Nullable String arguments) {
        Map<String, Object> json = jsonObjectOf(arguments);
        if (json == null) return null;
        Boolean done = booleanOf(json.get("done"));
        if (done == null) return null;
        String reason = json.get("reason") instanceof String r && !r.isBlank() ? r.strip() : "";
        if (reason.isEmpty()) return null;
        String answer = json.get("answer") instanceof String a ? a.strip() : "";
        return new Decision(done, reason, answer);
    }

    private static @Nullable Boolean booleanOf(@Nullable Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s.strip())) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(s.strip())) return Boolean.FALSE;
        }
        return null;
    }
}
