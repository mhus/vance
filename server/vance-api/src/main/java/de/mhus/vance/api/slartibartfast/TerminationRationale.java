package de.mhus.vance.api.slartibartfast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Summary of <em>why</em> a Slartibartfast run ended in
 * {@link ArchitectStatus#DONE} — recorded into
 * {@link ArchitectState#getTerminationRationale()} during PERSISTING
 * and exported in the DONE-payload + the {@code audit.json} sidecar.
 *
 * <p>Distinct from the {@link ValidationCheck}-by-check report:
 * this is the <em>convergence narrative</em>. Tells a reader (or
 * an auditing engine) what assumptions were taken, what coverage
 * was achieved, and how many iterations it took to get there.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TerminationRationale {

    /** Rule-ids of every {@link ValidationCheck} that ultimately
     *  passed in the final iteration (the ones that mattered).
     *  Failed checks from earlier iterations are <em>not</em>
     *  listed — they're in the iteration history. */
    @Builder.Default
    private List<String> passedChecks = new ArrayList<>();

    /** Foreign keys into {@link Criterion#getId()} for criteria
     *  with {@link CriterionOrigin#USER_STATED} that the planner
     *  satisfied via at least one subgoal. */
    @Builder.Default
    private List<String> statedCriteriaSatisfied = new ArrayList<>();

    /** Foreign keys for high-confidence inferred criteria that
     *  were taken for granted (no inbox confirmation). The
     *  recipe consumer should review these — they're what the
     *  planner decided <em>without</em> asking. */
    @Builder.Default
    private List<String> assumedCriteriaTakenForGranted = new ArrayList<>();

    /** Foreign keys for inferred criteria the user explicitly
     *  confirmed via inbox during CONFIRMING. */
    @Builder.Default
    private List<String> assumedCriteriaUserConfirmed = new ArrayList<>();

    /** Foreign keys for inferred criteria the user rejected.
     *  Subgoals supporting these were dropped or modified. */
    @Builder.Default
    private List<String> assumedCriteriaUserRejected = new ArrayList<>();

    /** {@link Criterion#getId()} → list of
     *  {@link Subgoal#getId()} that address it. Lets a reviewer
     *  trace each acceptance criterion to the planning steps
     *  that satisfy it. */
    @Builder.Default
    private Map<String, List<String>> criterionCoverage = new LinkedHashMap<>();

    /** Fraction of subgoals that are <em>not</em> speculative.
     *  1.0 = fully evidence-grounded plan; below the recipe's
     *  {@code maxSpeculativeRatio} threshold means VALIDATING
     *  would have rejected it (so this should always be above
     *  threshold in DONE state). */
    private double evidenceCoverage;

    /** Total {@link PhaseIteration} entries the run produced. */
    private int iterationCount;

    /** Number of {@link RecoveryRequest}s consumed during the
     *  run — i.e. how often a downstream gate sent control
     *  upstream. Higher number = more conflict between phases. */
    private int recoveryEvents;

    /** Final confidence the planner emits for the produced
     *  recipe. Inherits from
     *  {@link RecipeDraft#getConfidence()}; the same number is
     *  exported on the DONE-payload. */
    private double finalConfidence;
}
