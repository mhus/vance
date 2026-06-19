package de.mhus.vance.brain.thinkengine.action;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Decides what to do after a {@link StructuredActionEngine} action-loop
 * exhausted its {@code maxIters} budget without the LLM committing to a
 * final action.
 *
 * <p>Two outcomes:
 * <ul>
 *   <li>{@link Judgment#extend(String) extend} — the model is making
 *       genuine progress; the engine should re-invoke the loop with a
 *       fresh smaller budget. The engine is responsible for capping
 *       the total number of extensions.</li>
 *   <li>{@link Judgment#synthesize(String, String) synthesize} — the
 *       model is looping or has gathered enough; the judge returns a
 *       user-facing answer text that the engine uses as the
 *       turn-outcome reply (replacing the LLM's mid-research "let me
 *       look that up" free-text that the legacy fallback path used to
 *       surface).</li>
 * </ul>
 *
 * <p>Backed by the {@code action-loop-judge}
 * {@link LightLlmService} recipe — a single non-spawnable LLM call
 * per max-iters hit, costed on the user's tenant. On any failure
 * (recipe missing, schema budget exhausted, blank reply) the judge
 * downgrades to {@code synthesize} with the best gathered text as
 * the answer; refusing to terminate the turn would re-create the
 * very deadlock pattern this service exists to prevent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActionLoopJudgeService {

    static final String DEFAULT_RECIPE_NAME = "action-loop-judge";

    /** Same loose shape Discovery uses — we validate semantically below. */
    private static final Map<String, Object> SCHEMA = Map.of("type", "object");

    private final LightLlmService lightLlm;

    public Judgment judge(JudgeRequest req) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("userGoal", req.userGoal() == null ? "" : req.userGoal());
        vars.put("gatheredText", req.gatheredText() == null ? "" : req.gatheredText());
        vars.put("toolsUsed", formatToolsUsed(req.toolsUsed()));
        vars.put("iterations", req.iterations());
        vars.put("extensionsLeft", req.extensionsLeft());

        ThinkProcessDocument process = req.process();
        Map<String, Object> raw;
        try {
            raw = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(DEFAULT_RECIPE_NAME)
                    .userPrompt("Judge the action loop above.")
                    .pebbleVars(vars)
                    .schema(SCHEMA)
                    .tenantId(process.getTenantId())
                    .projectId(process.getProjectId())
                    .processId(process.getId())
                    .build());
        } catch (LightLlmException e) {
            // SchemaValidationException extends LightLlmException —
            // single catch covers both the "schema budget exhausted"
            // and the "recipe missing / provider broken" paths.
            log.warn("ActionLoopJudge id='{}' LLM failed ({}) — falling back to synthesize with gathered text",
                    process.getId(), e.toString());
            return Judgment.synthesize(safeFallback(req), "judge-llm-failed");
        }

        String decision = stringOrNull(raw.get("decision"));
        String reason = stringOrNull(raw.get("reason"));
        if ("extend".equalsIgnoreCase(decision) && req.extensionsLeft() > 0) {
            log.info("ActionLoopJudge id='{}' decision=extend reason='{}'",
                    process.getId(), reason);
            return Judgment.extend(reason);
        }
        // synthesize, or "extend" forbidden (extensionsLeft==0), or
        // any unexpected decision string — collapse to synthesize.
        String answer = stringOrNull(raw.get("answer"));
        if (answer == null || answer.isBlank()) {
            answer = safeFallback(req);
            log.warn("ActionLoopJudge id='{}' decision={} but answer empty — using gathered text fallback",
                    process.getId(), decision);
        } else {
            log.info("ActionLoopJudge id='{}' decision=synthesize reason='{}' answer-chars={}",
                    process.getId(), reason, answer.length());
        }
        return Judgment.synthesize(answer, reason == null ? "synthesize" : reason);
    }

    /**
     * When the judge can't deliver a fresh answer, we fall back to the
     * gathered free text rather than blocking the user — same shape as
     * the legacy action-loop fallback, but explicitly logged so we know
     * the judge didn't really do its job.
     */
    private static String safeFallback(JudgeRequest req) {
        String gathered = req.gatheredText();
        if (gathered != null && !gathered.isBlank()) {
            return gathered.trim();
        }
        // Last-resort literal — engine should still surface SOMETHING
        // rather than leave the user staring at an empty chat.
        return "Ich konnte zu deiner Frage in diesem Durchlauf keine "
                + "belastbare Antwort produzieren — frag mich gerne "
                + "konkreter oder lass uns einen anderen Weg probieren.";
    }

    private static String formatToolsUsed(@Nullable List<String> toolsUsed) {
        if (toolsUsed == null || toolsUsed.isEmpty()) {
            return "(none recorded)";
        }
        return String.join("\n", toolsUsed);
    }

    private static @Nullable String stringOrNull(@Nullable Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    /** Input bundle for {@link #judge(JudgeRequest)}. */
    public record JudgeRequest(
            ThinkProcessDocument process,
            String userGoal,
            String gatheredText,
            List<String> toolsUsed,
            int iterations,
            int extensionsLeft) {}

    /**
     * Decision the engine acts on. {@link #extend()} returns
     * {@code true} → run another loop round; otherwise the engine
     * uses {@link #synthesizedAnswer()} as the turn-outcome text.
     */
    public record Judgment(
            boolean extend,
            @Nullable String synthesizedAnswer,
            String reason) {

        public static Judgment extend(@Nullable String reason) {
            return new Judgment(true, null, reason == null ? "extend" : reason);
        }

        public static Judgment synthesize(String answer, String reason) {
            return new Judgment(false, answer, reason);
        }
    }
}
