package de.mhus.vance.brain.slartibartfast;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.Claim;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import de.mhus.vance.api.slartibartfast.FramedGoal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Lifts file-path conventions discovered during CLASSIFYING into
 * synthetic acceptance criteria so the rest of the pipeline
 * (DECOMPOSING, PROPOSING, VALIDATING) treats them as first-class
 * deliverables.
 *
 * <h2>The problem</h2>
 *
 * Kits like {@code school-essay} ship an {@code OUTPUT.md} that
 * names the expected artifact paths
 * ({@code essay/final-essay.md}, {@code essay/chapters/<NN>-<slug>.md},
 * …). CLASSIFYING extracts these as evidence claims ("the file
 * essay/final-essay.md is the consolidated text written at the end of
 * the pipeline"), but FRAMING only mines the user's text for
 * acceptance criteria — the user typically doesn't repeat the
 * convention paths in their request. The result: paths exist in
 * {@code evidenceClaims} but never reach
 * {@code acceptanceCriteria}, so PROPOSING isn't pressured to emit
 * a persistence phase, VALIDATING has nothing to enforce, and the
 * generated Vogon recipe ends without writing {@code essay/...} —
 * the symptom we hit live yesterday and this morning.
 *
 * <h2>The lift</h2>
 *
 * Right after CLASSIFYING completes (before DECOMPOSING starts),
 * the engine calls {@link #lift(ArchitectState)}. The lifter:
 *
 * <ol>
 *   <li>Scans every claim's {@code text} and {@code quote} for
 *       matches of {@link #PATH_PATTERN} — multi-segment slash
 *       paths with a known file extension.</li>
 *   <li>Filters out paths that already appear in any existing
 *       acceptanceCriterion text (idempotent — never double-emits
 *       when the user happens to mention a path verbatim or the
 *       lifter ran on a recovery loop).</li>
 *   <li>Emits one synthetic {@link Criterion} per remaining path
 *       with {@code origin = INFERRED_DOMAIN} and confidence 0.8.
 *       The text is a stable predicate: "The recipe must persist
 *       its output at `<path>` via doc_write_text." VALIDATING's
 *       {@code RULE_PATH_OUTPUTS_PERSISTED} rule extracts the path
 *       back via the back-tick pattern; the tool name in the
 *       wording steers the LLM toward the upsert tool (retry-safe
 *       on recovery loops) rather than the create-only one.</li>
 * </ol>
 *
 * Synthetic criteria pick {@code INFERRED_DOMAIN} (not
 * {@code INFERRED_CONVENTION}) because the paths come from
 * kit-specific OUTPUT.md, not from a universal "written content
 * gets saved" rule. Confidence 0.8 keeps them above the default
 * CONFIRMING threshold so they bypass the inbox question — these
 * are unambiguous artifact addresses, not weak inferences.
 *
 * <p>Pure-function utility; no LLM call. Cheap; safe to run on
 * every CLASSIFYING completion and on recovery loops.
 */
@Component
@Slf4j
public class PathCriteriaLifter {

    /**
     * Matches multi-segment paths with a recognised text-file
     * extension. Examples that match:
     * {@code essay/final-essay.md},
     * {@code essay/chapters/01-intro.md},
     * {@code _vance/config/project-kits.yaml}.
     * Anchored at a non-word boundary so paths embedded in prose
     * are picked up; the extension allowlist keeps URLs and
     * sentence fragments from leaking through.
     */
    private static final Pattern PATH_PATTERN = Pattern.compile(
            // Leading boundary: line-start, whitespace, or punctuation
            // commonly wrapping a path in prose. `\b` is NOT valid
            // inside a character class in Java regex, so we list the
            // exact characters instead — covers quote, backtick, paren,
            // bracket, angle.
            "(?:^|[\\s\"'`(\\[<])"
                    + "((?:[A-Za-z0-9_][A-Za-z0-9_.-]*/)+"
                    + "[A-Za-z0-9_][A-Za-z0-9_.-]*"
                    + "\\.(?:md|markdown|txt|yaml|yml|json|csv|pdf))"
                    + "(?:$|[\\s\"'`)\\]>,;:!?])");

    /** Confidence assigned to lifted criteria. High enough to
     *  bypass the default {@code confirmationThreshold} (0.5),
     *  low enough that operators can tune up to surface them in
     *  CONFIRMING if they want kit-author review. */
    private static final double LIFTED_CONFIDENCE = 0.8;

    /**
     * Append synthetic path-output criteria to
     * {@code state.acceptanceCriteria}. No-op when CLASSIFYING
     * produced no evidence with paths, or when every discovered
     * path is already covered by an existing criterion.
     *
     * <p>Returns the list of paths that were lifted in this call —
     * useful for logging and for VALIDATING to know which AC
     * entries are persistence-required.
     */
    public List<String> lift(ArchitectState state) {
        if (state == null) return List.of();
        FramedGoal goal = state.getGoal();
        List<Claim> claims = state.getEvidenceClaims();
        if (claims == null || claims.isEmpty()) {
            return List.of();
        }

        // 1. Collect every distinct path mentioned anywhere in claims.
        Set<String> claimPaths = new LinkedHashSet<>();
        for (Claim c : claims) {
            extractPaths(c.getText(), claimPaths);
            extractPaths(c.getQuote(), claimPaths);
        }
        if (claimPaths.isEmpty()) {
            return List.of();
        }

        // 2. Filter out paths already named by an existing criterion
        //    (idempotent — a recovery loop or a user who happened to
        //    paste the path verbatim won't double-emit).
        List<Criterion> existingCriteria = state.getAcceptanceCriteria();
        Set<String> alreadyCovered = new LinkedHashSet<>();
        for (Criterion c : existingCriteria) {
            extractPaths(c.getText(), alreadyCovered);
        }
        claimPaths.removeAll(alreadyCovered);
        if (claimPaths.isEmpty()) {
            return List.of();
        }

        // 3. Emit synthetic criteria. Stable id-prefix so a recovery
        //    loop can still match by id and avoid duplicates even if
        //    text wording shifts. Numbered from the next free slot
        //    after existing criteria.
        int nextIdx = existingCriteria.size() + 1;
        for (String path : claimPaths) {
            String id = "cr" + (nextIdx++) + "-output-"
                    + path.toLowerCase(Locale.ROOT)
                            .replaceAll("[^a-z0-9]+", "-")
                            .replaceAll("-+", "-")
                            .replaceAll("^-|-$", "");
            Criterion lifted = Criterion.builder()
                    .id(id)
                    .text("The recipe must persist its output at `"
                            + path + "` via doc_write_text.")
                    .origin(CriterionOrigin.INFERRED_DOMAIN)
                    .confidence(LIFTED_CONFIDENCE)
                    .build();
            existingCriteria.add(lifted);
        }

        log.info("PathCriteriaLifter: lifted {} path-criteria from {} evidence claim(s) — paths: {}",
                claimPaths.size(), claims.size(), claimPaths);

        // Mirror into the FramedGoal's statedCriteria for the
        // downstream prompt-render path that reads from there.
        // FramedGoal carries the originally-stated criteria; the
        // synthetic ones live alongside as inferred additions.
        if (goal != null && goal.getStatedCriteria() != null) {
            // Stated-criteria list is the user-stated snapshot —
            // don't touch it. acceptanceCriteria (which IS the
            // working list PROPOSING reads) already grew above.
        }

        return List.copyOf(claimPaths);
    }

    /** Walk a string and collect every PATH_PATTERN match into
     *  {@code into}. Null-safe; whitespace-trim the captures. */
    private static void extractPaths(@org.jspecify.annotations.Nullable String text,
                                     Set<String> into) {
        if (text == null || text.isEmpty()) return;
        Matcher m = PATH_PATTERN.matcher(text);
        while (m.find()) {
            String hit = m.group(1).trim();
            if (!hit.isEmpty()) into.add(hit);
        }
    }
}
