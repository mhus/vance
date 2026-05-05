package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.vogon.DeciderCase;
import de.mhus.vance.api.vogon.DeciderSpec;
import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import de.mhus.vance.api.vogon.StrategyState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Evaluates a worker phase's {@link DeciderSpec} against the worker's
 * reply. Pure StrategyState mutator — token-matches the reply against
 * {@link DeciderSpec#getOptions()} (case-insensitive, word-boundary,
 * first occurrence wins), persists the chosen token as the
 * {@code <storeAs>} flag, then runs the matching case's {@code do:}
 * list via {@link BranchActionExecutor}.
 *
 * <p>If no option appears in the reply the evaluator returns
 * {@link Outcome#NEEDS_CORRECTION} with a hint listing the allowed
 * tokens — the engine forwards it as a re-prompt and counts attempts
 * against {@link DeciderSpec#getMaxCorrections()}.
 *
 * <p>See {@code specification/vogon-engine.md} §2.6.
 */
final class DeciderEvaluator {

    record Result(
            Outcome outcome,
            @Nullable String correctionHint,
            BranchActionExecutor.@Nullable Result branchResult,
            @Nullable String chosenToken) {

        static Result needsCorrection(String hint) {
            return new Result(Outcome.NEEDS_CORRECTION, hint, null, null);
        }

        static Result completed(String token, BranchActionExecutor.Result branch) {
            return new Result(Outcome.COMPLETED, null, branch, token);
        }
    }

    enum Outcome { COMPLETED, NEEDS_CORRECTION }

    private DeciderEvaluator() {}

    static Result evaluate(
            StrategySpec strategy,
            StrategyState state,
            PhaseSpec phase,
            String phaseKey,
            DeciderSpec spec,
            @Nullable String workerReply) {
        if (workerReply == null || workerReply.isBlank()) {
            return Result.needsCorrection(missingTokenHint(spec));
        }
        String chosen = matchToken(workerReply, spec.getOptions());
        if (chosen == null) {
            return Result.needsCorrection(missingTokenHint(spec));
        }
        // Persist: chosen string under storeAs; full reply already in
        // phaseArtifacts.<phaseKey>.result (set by the engine).
        state.getFlags().put(spec.getStoreAs(), chosen);
        // Stash the chosen token + raw reply on the artifact for audit.
        Map<String, Object> artifact = state.getPhaseArtifacts().get(phaseKey);
        if (artifact == null) {
            artifact = new LinkedHashMap<>();
            state.getPhaseArtifacts().put(phaseKey, artifact);
        }
        Map<String, Object> deciderOutput = new LinkedHashMap<>();
        deciderOutput.put("chosen", chosen);
        artifact.put("deciderOutput", deciderOutput);

        // First case whose 'when' matches the chosen token (case-insensitive).
        DeciderCase matched = null;
        for (DeciderCase c : spec.getCases()) {
            if (c.getWhen() != null && c.getWhen().equalsIgnoreCase(chosen)) {
                matched = c;
                break;
            }
        }
        if (matched == null) {
            // The strategy-load validator already enforces that every case
            // 'when' is a member of options, but the chosen token may still
            // not be referenced by any case (some options have no case).
            return Result.completed(chosen, BranchActionExecutor.Result.continueRunning());
        }
        BranchActionExecutor.Result branch = BranchActionExecutor.execute(
                strategy, state, matched.getDoActions());
        return Result.completed(chosen, branch);
    }

    /**
     * Find the first allowed option that appears in {@code reply} as a
     * standalone word (letters/digits before/after rejected). Returns
     * the option in its original casing.
     */
    static @Nullable String matchToken(String reply, List<String> options) {
        String lower = reply.toLowerCase();
        int bestIdx = Integer.MAX_VALUE;
        String bestOpt = null;
        for (String opt : options) {
            int idx = findWord(lower, opt.toLowerCase());
            if (idx >= 0 && idx < bestIdx) {
                bestIdx = idx;
                bestOpt = opt;
            }
        }
        return bestOpt;
    }

    /** Word-boundary {@code indexOf} — letters/digits on either side
     *  of {@code needle} disqualify the match. */
    private static int findWord(String haystack, String needle) {
        if (needle.isEmpty()) return -1;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) return -1;
            boolean leftOk = idx == 0
                    || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            boolean rightOk = idx + needle.length() == haystack.length()
                    || !Character.isLetterOrDigit(
                            haystack.charAt(idx + needle.length()));
            if (leftOk && rightOk) return idx;
            from = idx + 1;
        }
    }

    private static String missingTokenHint(DeciderSpec spec) {
        return "Reply must contain exactly one of: "
                + String.join(", ", spec.getOptions())
                + ". End your reply with that single token.";
    }
}
