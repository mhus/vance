package de.mhus.vance.addon.brain.nutrimat;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Nutrimat's loop judge — a single schema-bound {@link LightLlmService} call
 * that decides "continue or stop" at the two decision points the lab explores:
 *
 * <ul>
 *   <li><b>exhaustion</b> ({@code clubmate}): the iteration budget ran out —
 *       grant a fresh budget and keep looping, or synthesize the answer from
 *       what has been gathered.</li>
 *   <li><b>natural-stop candidate</b> ({@code salitos}): the model stopped
 *       calling tools — accept the draft as the reply, or push it to continue
 *       working.</li>
 *   <li><b>self-accounting</b> ({@code absint}): the model stopped calling
 *       tools — produce a non-empty report of what this loop did; the stop
 *       itself is accepted, nobody decides about continuing.</li>
 * </ul>
 *
 * <p>Deliberately an own implementation, not the productive strand's
 * {@code ActionLoopJudgeService} (independence is the lab's premise) — the
 * mechanism is a blueprint, the policy lives here and in the internal
 * judge recipes ({@code nutrimat-judge-clubmate}, {@code nutrimat-judge-salitos},
 * {@code nutrimat-judge-absint}).
 *
 * <p>Failure policy: a judge that cannot deliver never blocks the turn. The
 * exhausted judge degrades to {@code synthesize} with the gathered text, the
 * continue judge degrades to {@code done} — refusing to stop would re-create
 * the very deadlock the judge exists to prevent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NutrimatJudge {

    static final String EXHAUSTED_RECIPE = "nutrimat-judge-clubmate";
    static final String CONTINUE_RECIPE = "nutrimat-judge-salitos";
    static final String REPORT_RECIPE = "nutrimat-judge-absint";

    /** Same loose shape the discovery/judge calls use — validated semantically below. */
    private static final Map<String, Object> SCHEMA = Map.of("type", "object");

    private final LightLlmService lightLlm;

    /** Verdict at budget exhaustion: extend with a fresh budget, or stop. */
    public record ExhaustedJudgment(boolean extend, String text, String reason) {}

    /** Verdict at a natural-stop candidate: the draft is done, or not yet. */
    public record ContinueJudgment(boolean done, String nudge, String reason) {}

    /**
     * Non-empty account of what this loop round did ({@code absint}) — the
     * contract guarantees a usable report even when the judge call degrades.
     */
    public record RoundReport(String report) {}

    /**
     * {@code clubmate}'s decision point. {@code extend=true} carries a nudge for
     * the next round in {@code text}; {@code extend=false} carries the answer
     * to surface in {@code text}.
     */
    public ExhaustedJudgment judgeExhausted(
            ThinkProcessDocument process, String userGoal, String gatheredText, int iterations) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("userGoal", userGoal == null ? "" : userGoal);
        vars.put("gatheredText", gatheredText == null ? "" : gatheredText);
        vars.put("iterations", iterations);

        Map<String, Object> raw;
        try {
            raw = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(EXHAUSTED_RECIPE)
                    .userPrompt("Judge the exhausted loop above.")
                    .pebbleVars(vars)
                    .schema(SCHEMA)
                    .tenantId(process.getTenantId())
                    .projectId(process.getProjectId())
                    .processId(process.getId())
                    .build());
        } catch (LightLlmException e) {
            // SchemaValidationException extends LightLlmException — one catch
            // covers "schema budget exhausted" and "recipe/provider broken".
            log.warn(
                    "NutrimatJudge id='{}' exhausted-judge LLM failed ({}) — degrading to synthesize",
                    process.getId(),
                    e.toString());
            return new ExhaustedJudgment(false, safeGathered(gatheredText), "judge-llm-failed");
        }

        String decision = stringOrNull(raw.get("decision"));
        String reason = stringOrNull(raw.get("reason"));
        if ("extend".equalsIgnoreCase(decision)) {
            String nudge = stringOrNull(raw.get("nudge"));
            log.info("NutrimatJudge id='{}' exhausted-judge decision=extend reason='{}'", process.getId(), reason);
            return new ExhaustedJudgment(true, nudge == null ? "" : nudge, reason == null ? "extend" : reason);
        }
        String answer = stringOrNull(raw.get("answer"));
        if (answer == null || answer.isBlank()) {
            answer = safeGathered(gatheredText);
            log.warn(
                    "NutrimatJudge id='{}' exhausted-judge decision={} but answer empty — using gathered text",
                    process.getId(),
                    decision);
        } else {
            log.info(
                    "NutrimatJudge id='{}' exhausted-judge decision=synthesize reason='{}' answer-chars={}",
                    process.getId(),
                    reason,
                    answer.length());
        }
        return new ExhaustedJudgment(false, answer, reason == null ? "synthesize" : reason);
    }

    /**
     * {@code salitos}'s decision point — asked at every natural-stop
     * candidate. {@code done=true} accepts the draft; {@code done=false}
     * carries the nudge that keeps the loop going.
     */
    public ContinueJudgment judgeContinue(
            ThinkProcessDocument process, String userGoal, String draftText, int iterations) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("userGoal", userGoal == null ? "" : userGoal);
        vars.put("draftText", draftText == null ? "" : draftText);
        vars.put("iterations", iterations);

        Map<String, Object> raw;
        try {
            raw = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(CONTINUE_RECIPE)
                    .userPrompt("Judge the draft answer above.")
                    .pebbleVars(vars)
                    .schema(SCHEMA)
                    .tenantId(process.getTenantId())
                    .projectId(process.getProjectId())
                    .processId(process.getId())
                    .build());
        } catch (LightLlmException e) {
            log.warn(
                    "NutrimatJudge id='{}' continue-judge LLM failed ({}) — degrading to done",
                    process.getId(),
                    e.toString());
            return new ContinueJudgment(true, "", "judge-llm-failed");
        }

        String decision = stringOrNull(raw.get("decision"));
        String reason = stringOrNull(raw.get("reason"));
        if ("continue".equalsIgnoreCase(decision)) {
            String nudge = stringOrNull(raw.get("nudge"));
            log.info("NutrimatJudge id='{}' continue-judge decision=continue reason='{}'", process.getId(), reason);
            return new ContinueJudgment(false, nudge == null ? "" : nudge, reason == null ? "continue" : reason);
        }
        log.info("NutrimatJudge id='{}' continue-judge decision=done reason='{}'", process.getId(), reason);
        return new ContinueJudgment(true, "", reason == null ? "done" : reason);
    }

    /**
     * {@code absint}'s reporting point — fired at every natural-stop candidate
     * instead of a done/continue verdict. The call must deliver a non-empty
     * account of what this loop did; a judge that cannot deliver never blocks
     * the turn: it degrades to the draft text (or the fixed fallback), so
     * the report is never blank.
     */
    public RoundReport reportRound(ThinkProcessDocument process, String userGoal, String draftText, int iterations) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("userGoal", userGoal == null ? "" : userGoal);
        vars.put("draftText", draftText == null ? "" : draftText);
        vars.put("iterations", iterations);

        Map<String, Object> raw;
        try {
            raw = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(REPORT_RECIPE)
                    .userPrompt("Account for the loop round above.")
                    .pebbleVars(vars)
                    .schema(SCHEMA)
                    .tenantId(process.getTenantId())
                    .projectId(process.getProjectId())
                    .processId(process.getId())
                    .build());
        } catch (LightLlmException e) {
            // SchemaValidationException extends LightLlmException — one catch
            // covers "schema budget exhausted" and "recipe/provider broken".
            log.warn(
                    "NutrimatJudge id='{}' round-report LLM failed ({}) — degrading to the draft text",
                    process.getId(),
                    e.toString());
            return new RoundReport(safeGathered(draftText));
        }

        String report = stringOrNull(raw.get("report"));
        if (report == null) {
            log.warn("NutrimatJudge id='{}' round-report empty — degrading to the draft text", process.getId());
            return new RoundReport(safeGathered(draftText));
        }
        log.info("NutrimatJudge id='{}' round-report chars={}", process.getId(), report.length());
        return new RoundReport(report);
    }

    /** Never block the turn: when the judge has nothing, carry the gathered text. */
    private static String safeGathered(String gathered) {
        if (gathered != null && !gathered.isBlank()) {
            return gathered.trim();
        }
        return "I couldn't produce a solid answer to your question in this run — "
                + "feel free to ask me more specifically or let's try a different approach.";
    }

    private static String stringOrNull(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }
}
