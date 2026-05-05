package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.ScoreMatch;
import de.mhus.vance.api.vogon.ScorerCase;
import de.mhus.vance.api.vogon.ScorerSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import de.mhus.vance.api.vogon.StrategyState;
import de.mhus.vance.shared.util.JsonReplyExtractor;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Evaluates a worker phase's {@link ScorerSpec} against the worker's
 * reply. Pure StrategyState mutator — extracts JSON, validates the
 * mandatory {@code score: 0..1} field, persists every top-level
 * field as a {@code <storeAs>_<field>} flag plus the full object
 * under {@code phaseArtifacts[<phase>].scorerOutput}, then walks
 * {@link ScorerSpec#getCases()} and runs the first matching case's
 * {@code do:} list via {@link BranchActionExecutor}.
 *
 * <p>Schema-failure flow: returns a {@link Outcome#NEEDS_CORRECTION}
 * with a human-readable hint that the engine forwards to the worker
 * as a re-prompt. The engine counts attempts against
 * {@link ScorerSpec#getMaxCorrections()} and fails the phase when
 * exhausted.
 *
 * <p>See {@code specification/vogon-engine.md} §2.5.
 */
final class ScorerEvaluator {

    /** What happened when {@link #evaluate} processed a reply. */
    record Result(
            Outcome outcome,
            @Nullable String correctionHint,
            BranchActionExecutor.@Nullable Result branchResult) {

        static Result needsCorrection(String hint) {
            return new Result(Outcome.NEEDS_CORRECTION, hint, null);
        }

        static Result completed(BranchActionExecutor.Result branch) {
            return new Result(Outcome.COMPLETED, null, branch);
        }
    }

    enum Outcome {
        /** Reply parsed, score persisted, matching case (if any) ran. */
        COMPLETED,
        /** Reply didn't parse or schema invalid — engine should re-prompt. */
        NEEDS_CORRECTION
    }

    private ScorerEvaluator() {}

    /**
     * Evaluate {@code spec} against {@code workerReply}. {@code phaseKey}
     * is the qualified phase key under which {@code phaseArtifacts}
     * is keyed (top-level: {@code <name>}; sub-phase: {@code <loop>/<sub>}).
     */
    static Result evaluate(
            StrategySpec strategy,
            StrategyState state,
            PhaseSpec phase,
            String phaseKey,
            ScorerSpec spec,
            @Nullable String workerReply,
            ObjectMapper mapper) {
        if (workerReply == null || workerReply.isBlank()) {
            return Result.needsCorrection(
                    "Reply is empty. End your reply with a single JSON "
                            + "object containing 'score' (0..1) and the "
                            + "fields declared in your prompt schema.");
        }
        String json = JsonReplyExtractor.extractLastObject(workerReply);
        if (json == null) {
            return Result.needsCorrection(
                    "No JSON object found at the end of the reply. End "
                            + "your reply with a single JSON object — "
                            + "examples earlier in the text are tolerated, "
                            + "but the LAST object wins.");
        }
        Map<String, Object> root;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(json, Map.class);
            root = parsed;
        } catch (RuntimeException e) {
            return Result.needsCorrection(
                    "JSON is not valid: " + e.getMessage()
                            + ". Re-emit the final JSON object correctly.");
        }
        Object scoreRaw = root.get("score");
        if (!(scoreRaw instanceof Number scoreNum)) {
            return Result.needsCorrection(
                    "Required field 'score' is missing or not a number. "
                            + "Include {\"score\": <0..1 float>, ...} in your reply.");
        }
        double score = scoreNum.doubleValue();
        if (score < 0.0 || score > 1.0) {
            return Result.needsCorrection(
                    "'score' must be in [0.0, 1.0] — got " + score
                            + ". Re-emit with a normalised value.");
        }
        // Schema-declared fields: every top-level key in spec.schema must
        // exist in the reply (or surface a hint).
        if (spec.getSchema() != null) {
            for (String required : spec.getSchema().keySet()) {
                if (!root.containsKey(required)) {
                    return Result.needsCorrection(
                            "Required field '" + required
                                    + "' is missing in the JSON reply.");
                }
            }
        }
        // Persist: full object under storeAs, scalar fields as <storeAs>_<field>.
        String storeAs = spec.getStoreAs();
        Map<String, Object> flags = state.getFlags();
        flags.put(storeAs, root);
        for (Map.Entry<String, Object> e : root.entrySet()) {
            Object v = e.getValue();
            if (v == null) continue;
            if (v instanceof Number || v instanceof Boolean || v instanceof String) {
                flags.put(storeAs + "_" + e.getKey(), v);
            }
        }
        // Stash the full object as the phase's scorerOutput artifact.
        Map<String, Object> artifact = state.getPhaseArtifacts().get(phaseKey);
        if (artifact == null) {
            artifact = new LinkedHashMap<>();
            state.getPhaseArtifacts().put(phaseKey, artifact);
        }
        artifact.put("scorerOutput", root);

        // Switch over score: first matching case wins.
        ScorerCase matched = matchCase(spec, score);
        if (matched == null) {
            // No case matched and no default — silent pass-through.
            return Result.completed(BranchActionExecutor.Result.continueRunning());
        }
        BranchActionExecutor.Result branch = BranchActionExecutor.execute(
                strategy, state, matched.getDoActions());
        return Result.completed(branch);
    }

    private static @Nullable ScorerCase matchCase(ScorerSpec spec, double score) {
        for (ScorerCase c : spec.getCases()) {
            if (matches(c.getWhen(), score)) return c;
        }
        return null;
    }

    private static boolean matches(ScoreMatch when, double score) {
        if (when.isDefaultMatch()) return true;
        if (when.getScoreAtLeast() != null) return score >= when.getScoreAtLeast();
        if (when.getScoreBelow() != null) return score < when.getScoreBelow();
        if (when.getScoreBetween() != null) {
            double[] r = when.getScoreBetween();
            return score >= r[0] && score < r[1];
        }
        return false;
    }
}
