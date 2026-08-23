package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.frankie.FrankieTermination;
import de.mhus.vance.brain.trillian.TrillianWorkerEngine;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Lets a Trillian worker ask a question without ending itself.
 *
 * <p><b>Why a tool and not an instruction.</b> The worker prompt used to
 * say: to ask, reply in plain text and call nothing. A natural stop
 * leaves the process IDLE, which is exactly what was wanted. The model
 * asked through {@code trillian_done} anyway and closed itself, taking
 * everything it had worked out with it. In a tool-calling loop, "end the
 * turn by calling nothing" competes with every tool on the list and
 * loses — so asking gets a tool of its own, and the choice becomes one
 * between two named things rather than between a tool and an omission.
 *
 * <p>Mechanically this is the same exit as {@code trillian_done}: it
 * returns Frankie's {@code _terminate} so the loop stops. The difference
 * is what the engine does next — {@link TrillianWorkerEngine} sees the
 * marker left here and parks the worker IDLE instead of closing it, so
 * {@code process_steer} can carry the answer straight back into the
 * context that raised the question.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianAskTool implements Tool {

    /** The obstacle could clear on its own — worth looking again. */
    public static final String BLOCKER_STATE = "state";
    /** Only a human closes this one; looking again learns nothing. */
    public static final String BLOCKER_DECISION = "decision";

    /** engineParamOverrides key on the worker: which kind of obstacle. */
    public static final String PARAM_ASK_BLOCKER = "trillianAskBlocker";

    /**
     * engineParamOverrides key on the worker: which question is open.
     *
     * <p>A fingerprint of the question text, not the text itself — the
     * only thing anyone asks of it is whether the question that just came
     * back is the one that was already there.
     */
    public static final String PARAM_ASK_QUESTION = "trillianAskQuestion";

    /**
     * engineParamOverrides key on the worker: re-checks already offered
     * for the open question.
     *
     * <p>Lives with the tool that opens the question rather than with the
     * Nature that spends the budget: the budget belongs to the question,
     * and the question is raised here. A Nature reads and advances it
     * (see {@code TrillianNatureAdam}); nobody else writes it.
     */
    public static final String PARAM_ASK_PROBES = "trillianAskProbes";

    /** engineParamOverrides key on the worker: when the breaker opened. */
    public static final String PARAM_ASK_OPENED_AT = "trillianAskOpenedAt";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "question", Map.of(
                            "type", "string",
                            "description", "What you need to know, in one or two "
                                    + "sentences. Say what you already established and "
                                    + "what you would do with each possible answer — "
                                    + "whoever reads it cannot see your work."),
                    "blocker", Map.of(
                            "type", "string",
                            "enum", List.of(BLOCKER_STATE, BLOCKER_DECISION),
                            "description", "What is in your way. 'state' — something "
                                    + "about the world that could change on its own or "
                                    + "by someone else's hand: a locked file, a missing "
                                    + "document, a service that was down. 'decision' — a "
                                    + "choice only a human can make, which stays open "
                                    + "however long you wait. Answer honestly: a 'state' "
                                    + "gets re-checked for you before anyone is "
                                    + "disturbed, and claiming it for a decision only "
                                    + "wastes a turn confirming what you already know.")),
            "required", List.of("question", "blocker"));

    /**
     * Written into the worker's history right after the question, as the
     * turn's last word. USER role because that is the side the answer
     * will come from — the model has to read it as "the world replied",
     * not as another thought of its own.
     */
    static final String WAITING_RECEIPT =
            "Your question was delivered to the orchestrator and you are now waiting "
                    + "for an answer. Do not ask it again and do not restate the problem — "
                    + "if this is still the last thing in your history, the answer has "
                    + "simply not arrived yet. When it does, it appears as a new message "
                    + "and you continue from where you stopped.";

    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final de.mhus.vance.brain.progress.ProgressEmitter progressEmitter;

    @Override
    public String name() {
        return "trillian_ask";
    }

    @Override
    public String description() {
        return "Ask a question and wait. Use this when you cannot continue "
                + "without something only a human can decide — a blocked "
                + "target, a choice between two paths, a missing fact. You "
                + "stay alive with everything you have worked out; the answer "
                + "is delivered back to you and you carry on from where you "
                + "stopped. Do NOT use trillian_done for a question: that "
                + "ends you, and your work is lost.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(
            @Nullable Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("question");
        if (!(raw instanceof String question) || question.isBlank()) {
            throw new ToolException("'question' is required and must be a non-empty string");
        }
        String text = question.trim();
        if (ctx.processId() == null) {
            throw new ToolException("trillian_ask is only available inside a Trillian worker");
        }

        @Nullable ThinkProcessDocument process =
                thinkProcessService.findById(ctx.processId()).orElse(null);

        // The marker has to be set before the engine reaches its exit
        // branch — it is the only thing distinguishing this exit from a
        // finished one. It stays set while the worker is parked and is
        // cleared by TrillianWorkerEngine when the worker runs again,
        // which is what makes "is a question open?" answerable.
        thinkProcessService.setEngineParamOverride(
                ctx.processId(), TrillianWorkerEngine.PARAM_ASK_PENDING, true);
        // Whether a second attempt could mean anything is knowable now and
        // not later: the worker just met the obstacle. Reconstructing it
        // from the question text afterwards would be guesswork, and the
        // default falls to 'decision' because a pointless retry costs more
        // than a skipped one.
        thinkProcessService.setEngineParamOverride(
                ctx.processId(), PARAM_ASK_BLOCKER, blocker(params));
        startOrContinueEpisode(ctx.processId(), process, text);

        // Persist and then hand the question to the parent.
        //
        // The push is the load-bearing half. Frankie routes a worker's
        // words to its parent from the natural-stop path only — this exit
        // leaves through _terminate, where nothing notifies anyone. A
        // parked worker whose question never arrives is worse than one
        // that closed: the loop waits, the human hears nothing, and
        // nothing in the system looks wrong.
        try {
            if (process != null) {
                chatMessageService.append(ChatMessageDocument.builder()
                        .tenantId(process.getTenantId())
                        .sessionId(process.getSessionId())
                        .thinkProcessId(process.getId())
                        .role(ChatRole.ASSISTANT)
                        .content(text)
                        .build());
                // Same channel a natural stop would use, so the loop sees
                // the <worker-reply> shape it already knows.
                progressEmitter.emitReply(process, text, /*inResponseToAt*/ null, null);

                // And a receipt, in the worker's own history.
                //
                // Without it the next turn reads: my last message states a
                // problem, and nothing follows. No confirmation, no answer.
                // The obvious inference is that the attempt did not land,
                // so the model states the problem again — observed live,
                // three identical questions in twelve seconds. The tool
                // result cannot carry this: results live in the turn's
                // in-memory message list, and that turn ends here; the
                // next prompt is rebuilt from chat history, where they
                // leave no trace.
                chatMessageService.append(ChatMessageDocument.builder()
                        .tenantId(process.getTenantId())
                        .sessionId(process.getSessionId())
                        .thinkProcessId(process.getId())
                        .role(ChatRole.USER)
                        .content(WAITING_RECEIPT)
                        .build());
            }
        } catch (RuntimeException e) {
            log.warn("trillian_ask: could not deliver the question of process='{}': {}",
                    ctx.processId(), e.toString());
        }
        log.info("Trillian worker id='{}' asks: {}", ctx.processId(),
                text.length() > 120 ? text.substring(0, 120) + "…" : text);

        Map<String, Object> out = new LinkedHashMap<>();
        // Same exit as trillian_done — leave the loop. The engine decides
        // that this one parks rather than closes.
        out.put(FrankieTermination.RESULT_TERMINATE_KEY, true);
        out.put("status", "asked");
        out.put("note", "You are now waiting. The answer will arrive as a new "
                + "message and you continue from here — do not repeat the work.");
        return out;
    }

    /**
     * Records which question is open and, when it is a new one, gives it
     * a fresh re-check budget.
     *
     * <p>The budget hangs on the question, not on the process. A worker
     * that spent its three probes on a locked file, got an answer, worked
     * on and then met a <em>different</em> obstacle would otherwise
     * inherit an exhausted breaker: the cheap re-check the second obstacle
     * deserves never happens, and the only thing left is the two-hour
     * probe.
     *
     * <p>The same question coming back keeps its budget — that is the case
     * the breaker exists for. "Same" is decided on the text, because
     * nothing else distinguishes a re-ask after a nudge from a new
     * question raised in the same turn the previous answer arrived.
     */
    private void startOrContinueEpisode(
            String processId, @Nullable ThinkProcessDocument process, String question) {
        String fingerprint = fingerprintOf(question);
        if (fingerprint.equals(currentQuestion(process))) {
            return;
        }
        thinkProcessService.setEngineParamOverride(processId, PARAM_ASK_QUESTION, fingerprint);
        thinkProcessService.setEngineParamOverride(processId, PARAM_ASK_PROBES, null);
        thinkProcessService.setEngineParamOverride(processId, PARAM_ASK_OPENED_AT, null);
    }

    private static @Nullable String currentQuestion(@Nullable ThinkProcessDocument process) {
        Map<String, Object> overrides =
                process == null ? null : process.getEngineParamOverrides();
        Object raw = overrides == null ? null : overrides.get(PARAM_ASK_QUESTION);
        return raw instanceof String s ? s : null;
    }

    /**
     * Whitespace and case are noise here: a model that re-asks the same
     * thing rarely re-types it byte for byte. A collision would only cost
     * one question the fresh budget it deserved, which is why a plain
     * string hash is enough.
     */
    static String fingerprintOf(String question) {
        String normalised = question.replaceAll("\\s+", " ").strip()
                .toLowerCase(java.util.Locale.ROOT);
        return Integer.toHexString(normalised.hashCode());
    }

    /** {@code state} only when it says so; everything else is a decision. */
    private static String blocker(@Nullable Map<String, Object> params) {
        Object raw = params == null ? null : params.get("blocker");
        return raw instanceof String b && BLOCKER_STATE.equalsIgnoreCase(b.strip())
                ? BLOCKER_STATE
                : BLOCKER_DECISION;
    }
}
