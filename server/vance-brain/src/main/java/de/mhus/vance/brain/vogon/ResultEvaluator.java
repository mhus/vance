package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.vogon.ResultSpec;
import de.mhus.vance.api.vogon.StrategyState;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Evaluates a strategy's {@link ResultSpec} into a
 * {@link ResultEvaluator.Outcome} (text + payload). Used by
 * {@code VogonEngine} at DONE / FAILED to produce the
 * {@code REPLY}-channel payload sent to the parent.
 *
 * <p>Two-phase evaluation:
 *
 * <ol>
 *   <li><b>fields</b> — each entry is a {@code ${…}} template
 *       resolved via {@link VogonSubstitutor#resolveTyped}. The
 *       type-preserving form means {@code "${flags.draftWordCount}"}
 *       stays an {@code Integer} in the resulting payload; plain
 *       text or interpolated templates become strings as usual.</li>
 *   <li><b>text</b> — rendered via
 *       {@link VogonSubstitutor#applyWithResult} with the
 *       just-evaluated fields exposed as {@code ${result.X}}.</li>
 * </ol>
 *
 * <p>See {@code specification/vogon-engine.md} §3.2 and
 * {@code planning/vogon-result-spec.md} §3.
 *
 * <p>Runtime failures (unresolved references, lookups that throw)
 * bubble up to the caller. {@code VogonEngine} catches them and
 * falls back to {@code summarizeForParent}; the strategy run
 * itself stays successful.
 */
public final class ResultEvaluator {

    private ResultEvaluator() {}

    /**
     * Outcome of a successful evaluation. {@link #text} is the
     * user-facing REPLY body (may be {@code null}/blank when the
     * ResultSpec didn't set {@code text:}); {@link #payload} is the
     * structured field map (never null, may be empty).
     */
    public record Outcome(@Nullable String text, Map<String, Object> payload) {}

    /**
     * Runs the two-phase evaluation. Returns {@code null} when
     * {@code spec} itself is {@code null} — the caller treats that
     * as "no result block, fall back to the default".
     */
    public static @Nullable Outcome evaluate(
            @Nullable ResultSpec spec,
            Map<String, Object> params,
            StrategyState state) {
        if (spec == null) return null;
        VogonSubstitutor sub = new VogonSubstitutor(params, state);
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, String> fieldSpecs = spec.getFields() == null
                ? Map.of() : spec.getFields();
        for (Map.Entry<String, String> entry : fieldSpecs.entrySet()) {
            // resultFields=null here — ${result.X} is intentionally
            // out of scope inside the fields block (spec §3.2 forbids
            // self-reference / cyclic scope).
            Object value = sub.resolveTyped(entry.getValue(), null);
            fields.put(entry.getKey(), value);
        }
        String text = spec.getText() == null || spec.getText().isBlank()
                ? null
                : sub.applyWithResult(spec.getText(), fields);
        return new Outcome(text, fields);
    }
}
