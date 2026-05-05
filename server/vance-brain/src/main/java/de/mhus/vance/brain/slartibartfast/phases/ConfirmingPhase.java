package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import de.mhus.vance.api.slartibartfast.FramedGoal;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CONFIRMING phase — partitions {@link FramedGoal#getAssumedCriteria()}
 * by confidence and produces the unified
 * {@link ArchitectState#getAcceptanceCriteria()} working set the
 * rest of the lifecycle plans against.
 *
 * <p>Decision logic:
 * <ul>
 *   <li>Every {@link FramedGoal#getStatedCriteria()} entry passes
 *       through unconditionally — the user said it.</li>
 *   <li>Assumed criteria with
 *       {@link Criterion#getConfidence()} ≥
 *       {@link ArchitectState#getConfirmationThreshold()} pass
 *       through with audit note (the planner takes them for
 *       granted because the inference is firm enough).</li>
 *   <li>Assumed criteria below threshold are dropped in this
 *       minimal v1 implementation — recorded as
 *       {@link ValidationCheck} entries with
 *       rule {@code low-confidence-criterion-dropped}.</li>
 * </ul>
 *
 * <p>M6 extends this with an inbox-dialog: low-confidence assumed
 * criteria become inbox questions, the user's answer flips them
 * to {@link CriterionOrigin#USER_CONFIRMED} (passes through) or
 * confirms the drop. Until then dropping is silent-ish — visible
 * in the audit but not blocking.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConfirmingPhase {

    /**
     * Mutates {@code state.acceptanceCriteria} and appends a
     * {@link PhaseIteration} plus zero-or-more
     * {@link ValidationCheck} entries describing dropped
     * low-confidence criteria.
     *
     * <p>Idempotent on re-entry: re-running the phase rebuilds
     * {@code acceptanceCriteria} from scratch off
     * {@code state.goal}. That's deliberate — a recovery
     * rollback to CONFIRMING (e.g. after FRAMING re-prompted)
     * must produce a fresh working set, not pile onto the
     * old one.
     */
    public void execute(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {

        FramedGoal goal = state.getGoal();
        if (goal == null) {
            log.warn("Slartibartfast id='{}' CONFIRMING entered without "
                            + "goal — FRAMING didn't populate it",
                    process.getId());
            state.setFailureReason("CONFIRMING entered without a FramedGoal "
                    + "— FRAMING must run first");
            return;
        }

        double threshold = state.getConfirmationThreshold();

        List<Criterion> accepted = new ArrayList<>();
        List<Criterion> dropped = new ArrayList<>();

        // 1. Every stated criterion passes through unconditionally.
        accepted.addAll(goal.getStatedCriteria());

        // 2. Assumed criteria split by confidence.
        for (Criterion c : goal.getAssumedCriteria()) {
            // USER_CONFIRMED entries (set by a future M6 inbox flow)
            // pass regardless of recorded confidence — the user
            // said yes.
            if (c.getOrigin() == CriterionOrigin.USER_CONFIRMED
                    || c.getConfidence() >= threshold) {
                accepted.add(c);
            } else {
                dropped.add(c);
            }
        }

        state.setAcceptanceCriteria(accepted);

        // 3. Audit dropped entries as informational ValidationChecks.
        // Replace the current report rather than append — the
        // CONFIRMING-tier checks supersede whatever a previous
        // iteration wrote. Future-phase checks will append.
        List<ValidationCheck> report = new ArrayList<>();
        report.add(ValidationCheck.builder()
                .rule("acceptance-criteria-built")
                .passed(true)
                .message(accepted.size() + " accepted ("
                        + goal.getStatedCriteria().size() + " stated, "
                        + (accepted.size() - goal.getStatedCriteria().size())
                        + " high-conf assumed); " + dropped.size()
                        + " low-conf assumed dropped")
                .build());
        for (Criterion d : dropped) {
            report.add(ValidationCheck.builder()
                    .rule("low-confidence-criterion-dropped")
                    .passed(true)   // informational, not a failure
                    .offendingId(d.getId())
                    .message("dropped: confidence " + d.getConfidence()
                            + " below threshold " + threshold + " — '"
                            + abbrev(d.getText(), 80) + "'")
                    .build());
        }
        state.setValidationReport(report);

        // 4. Append iteration to the audit log.
        appendIteration(state,
                "stated=" + goal.getStatedCriteria().size()
                        + ", assumed=" + goal.getAssumedCriteria().size()
                        + ", threshold=" + threshold,
                "accepted=" + accepted.size() + ", dropped=" + dropped.size(),
                PhaseIteration.IterationOutcome.PASSED);
    }

    private static void appendIteration(
            ArchitectState state,
            String inputSummary,
            String outputSummary,
            PhaseIteration.IterationOutcome outcome) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == ArchitectStatus.CONFIRMING).count() + 1;
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(PhaseIteration.builder()
                .iteration(attempt)
                .phase(ArchitectStatus.CONFIRMING)
                .triggeredBy(attempt == 1 ? "initial" : "recovery")
                .inputSummary(inputSummary)
                .outputSummary(outputSummary)
                .outcome(outcome)
                .build());
        state.setIterations(log);
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
