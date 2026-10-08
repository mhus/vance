package de.mhus.vance.addon.brain.nutrimat.affogato;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * affogato's critic — a single schema-bound {@link LightLlmService} call that
 * attacks the loop's draft answer. It reads the user's goal, the draft and
 * the tool trace (what the loop actually did and found), and answers
 * {@code accept} or {@code revise} with a critique.
 *
 * <p>Deliberately affogato's own service, not {@code NutrimatJudge}: the
 * critic is a property of this nature and must not leak into other loops.
 *
 * <p>Failure policy: fail-open. A critic that cannot deliver (provider
 * failure, schema miss, unknown verdict) accepts the draft — a broken critic
 * must never keep the loop from ending.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AffogatoCritic {

    static final String RECIPE = "nutrimat-critic-affogato";

    private static final Map<String, Object> SCHEMA = Map.of("type", "object");

    private final LightLlmService lightLlm;

    /** The critic's verdict: accept the draft, or revise it along the critique. */
    public record Verdict(boolean accept, String critique) {

        static Verdict acceptFallback(String why) {
            return new Verdict(true, why);
        }
    }

    public Verdict critique(ThinkProcessDocument process, String userGoal, String draftText, String toolTrace) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("userGoal", userGoal);
        vars.put("draftText", draftText);
        vars.put("toolTrace", toolTrace.isBlank() ? "(no tool calls in this turn)" : toolTrace);

        Map<String, Object> raw;
        try {
            raw = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(RECIPE)
                    .userPrompt("Criticise the draft answer above.")
                    .pebbleVars(vars)
                    .schema(SCHEMA)
                    .tenantId(process.getTenantId())
                    .projectId(process.getProjectId())
                    .processId(process.getId())
                    .build());
        } catch (LightLlmException e) {
            log.warn(
                    "AffogatoCritic id='{}' critic LLM failed ({}) — accepting the draft",
                    process.getId(),
                    e.toString());
            return Verdict.acceptFallback("critic unavailable — draft accepted");
        } catch (RuntimeException e) {
            log.warn(
                    "AffogatoCritic id='{}' critic call broke ({}) — accepting the draft",
                    process.getId(),
                    e.toString());
            return Verdict.acceptFallback("critic unavailable — draft accepted");
        }
        return verdictOf(raw);
    }

    /** Maps the critic's JSON onto a verdict — anything but a usable "revise" accepts. */
    static Verdict verdictOf(@Nullable Map<String, Object> raw) {
        if (raw == null) {
            return Verdict.acceptFallback("critic gave no verdict — draft accepted");
        }
        String verdict = stringOrNull(raw.get("verdict"));
        String critique = stringOrNull(raw.get("critique"));
        if ("revise".equalsIgnoreCase(verdict)) {
            if (critique == null) {
                return Verdict.acceptFallback("critic said revise without a critique — draft accepted");
            }
            return new Verdict(false, critique.strip());
        }
        if ("accept".equalsIgnoreCase(verdict)) {
            return new Verdict(true, critique == null ? "accepted" : critique.strip());
        }
        return Verdict.acceptFallback("critic gave an unknown verdict '" + verdict + "' — draft accepted");
    }

    private static @Nullable String stringOrNull(@Nullable Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }
}
