package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Claim;
import de.mhus.vance.api.slartibartfast.ClassificationKind;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.RecoveryRequest;
import de.mhus.vance.api.slartibartfast.Subgoal;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * BINDING phase — hard validator gate after DECOMPOSING. Enforces
 * the central rigor invariants: every subgoal is either evidence-
 * tied (with at least one FACT/EXAMPLE-tier claim) OR explicitly
 * speculative; every acceptance criterion is covered by at least
 * one subgoal; the speculative ratio stays below the recipe's
 * threshold. Failures don't fail the run — they set
 * {@link ArchitectState#setPendingRecovery} pointing back at
 * DECOMPOSING with a concrete corrective hint, and the engine
 * rolls control back on the next turn.
 *
 * <p>Pure logic — no LLM, no I/O. Idempotent: re-running rebuilds
 * the validationReport from scratch.
 *
 * <p>Recovery escalation: each rollback increments
 * {@link ArchitectState#getRecoveryCount()}; once it reaches
 * {@link ArchitectState#getMaxRecoveries()} the engine flips to
 * ESCALATED instead of looping back. BindingPhase itself doesn't
 * track this — it always emits a RecoveryRequest on failure and
 * lets the engine decide whether to honour it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BindingPhase {

    /** Validator rule-ids — referenced from
     *  {@link ValidationCheck#getRule()} and from
     *  {@link RecoveryRequest#getReason()}. Stable strings so
     *  audit dumps are greppable. */
    public static final String RULE_NO_DANGLING_CLAIM_REF =
            "subgoal-claim-ref-resolves";
    public static final String RULE_NO_DANGLING_CRITERION_REF =
            "subgoal-criterion-ref-resolves";
    public static final String RULE_EVIDENCE_OR_SPECULATIVE =
            "subgoal-has-evidence-or-marked-speculative";
    public static final String RULE_NOT_OPINION_ONLY =
            "non-speculative-subgoal-needs-fact-or-example";
    public static final String RULE_CRITERION_COVERAGE =
            "every-criterion-addressed-by-subgoal";
    public static final String RULE_SPECULATION_BOUND =
            "speculation-ratio-within-bound";

    /**
     * Mutates {@code state.validationReport} (rebuilt). On
     * violation also sets {@code state.pendingRecovery} pointing
     * at DECOMPOSING and appends a FAILED iteration; on pass
     * appends a PASSED iteration and leaves pendingRecovery null.
     */
    public void execute(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {

        if (state.getSubgoals().isEmpty()) {
            // Empty subgoals would already have failed
            // DecomposingPhase, but defend defensively here too.
            state.setFailureReason("BINDING entered with empty subgoals — "
                    + "DECOMPOSING must produce at least one");
            return;
        }

        List<ValidationCheck> report = new ArrayList<>();
        Set<String> claimIds = collectIds(state.getEvidenceClaims(),
                Claim::getId);
        Set<String> criterionIds = collectIds(state.getAcceptanceCriteria(),
                Criterion::getId);

        // 1. Per-subgoal checks: claim/criterion ref resolution,
        //    evidence-or-speculative invariant, FACT/EXAMPLE-tier.
        boolean anyHardFail = false;
        ValidationCheck firstFail = null;
        Set<String> coveredCriteria = new LinkedHashSet<>();
        long speculativeCount = 0;

        for (Subgoal sg : state.getSubgoals()) {
            // claim-ref resolution
            for (String ref : sg.getEvidenceRefs()) {
                if (!claimIds.contains(ref)) {
                    ValidationCheck v = ValidationCheck.builder()
                            .rule(RULE_NO_DANGLING_CLAIM_REF)
                            .passed(false)
                            .offendingId(sg.getId())
                            .message("subgoal '" + sg.getId()
                                    + "' references non-existent claim '"
                                    + ref + "'")
                            .build();
                    report.add(v);
                    if (firstFail == null) firstFail = v;
                    anyHardFail = true;
                }
            }
            // criterion-ref resolution
            for (String ref : sg.getCriterionRefs()) {
                if (!criterionIds.contains(ref)) {
                    ValidationCheck v = ValidationCheck.builder()
                            .rule(RULE_NO_DANGLING_CRITERION_REF)
                            .passed(false)
                            .offendingId(sg.getId())
                            .message("subgoal '" + sg.getId()
                                    + "' references non-existent criterion '"
                                    + ref + "'")
                            .build();
                    report.add(v);
                    if (firstFail == null) firstFail = v;
                    anyHardFail = true;
                } else {
                    coveredCriteria.add(ref);
                }
            }
            // evidence-or-speculative invariant (also enforced
            // structurally in DECOMPOSING, but checked here too
            // for defence-in-depth on hand-crafted subgoals).
            if (sg.isSpeculative()) {
                speculativeCount++;
                if (sg.getSpeculationRationale() == null
                        || sg.getSpeculationRationale().isBlank()) {
                    ValidationCheck v = ValidationCheck.builder()
                            .rule(RULE_EVIDENCE_OR_SPECULATIVE)
                            .passed(false)
                            .offendingId(sg.getId())
                            .message("subgoal '" + sg.getId()
                                    + "' is speculative but has no "
                                    + "speculationRationale")
                            .build();
                    report.add(v);
                    if (firstFail == null) firstFail = v;
                    anyHardFail = true;
                }
            } else if (sg.getEvidenceRefs().isEmpty()) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_EVIDENCE_OR_SPECULATIVE)
                        .passed(false)
                        .offendingId(sg.getId())
                        .message("subgoal '" + sg.getId()
                                + "' has no evidenceRefs and is not marked "
                                + "speculative")
                        .build();
                report.add(v);
                if (firstFail == null) firstFail = v;
                anyHardFail = true;
            } else {
                // Non-speculative subgoals must cite at least one
                // FACT or EXAMPLE — pure OPINION/OUTDATED support
                // is too weak.
                boolean hasFirmEvidence = false;
                for (String ref : sg.getEvidenceRefs()) {
                    Claim claim = findClaim(state, ref);
                    if (claim == null) continue; // dangling — already flagged
                    if (claim.getClassification() == ClassificationKind.FACT
                            || claim.getClassification() == ClassificationKind.EXAMPLE) {
                        hasFirmEvidence = true;
                        break;
                    }
                }
                if (!hasFirmEvidence) {
                    ValidationCheck v = ValidationCheck.builder()
                            .rule(RULE_NOT_OPINION_ONLY)
                            .passed(false)
                            .offendingId(sg.getId())
                            .message("subgoal '" + sg.getId()
                                    + "' is non-speculative but cites only "
                                    + "OPINION/OUTDATED claims — either find "
                                    + "FACT/EXAMPLE evidence or mark speculative")
                            .build();
                    report.add(v);
                    if (firstFail == null) firstFail = v;
                    anyHardFail = true;
                }
            }
        }

        // 2. Coverage check: every acceptance criterion is hit.
        for (String cid : criterionIds) {
            if (!coveredCriteria.contains(cid)) {
                ValidationCheck v = ValidationCheck.builder()
                        .rule(RULE_CRITERION_COVERAGE)
                        .passed(false)
                        .offendingId(cid)
                        .message("acceptance criterion '" + cid
                                + "' is not addressed by any subgoal")
                        .build();
                report.add(v);
                if (firstFail == null) firstFail = v;
                anyHardFail = true;
            }
        }

        // 3. Speculation-bound.
        double ratio = (double) speculativeCount / state.getSubgoals().size();
        if (ratio > state.getMaxSpeculativeRatio()) {
            ValidationCheck v = ValidationCheck.builder()
                    .rule(RULE_SPECULATION_BOUND)
                    .passed(false)
                    .offendingId(null)
                    .message("speculative ratio "
                            + String.format(java.util.Locale.ROOT, "%.2f", ratio)
                            + " exceeds bound "
                            + state.getMaxSpeculativeRatio()
                            + " (" + speculativeCount + "/"
                            + state.getSubgoals().size() + " speculative)")
                    .build();
            report.add(v);
            if (firstFail == null) firstFail = v;
            anyHardFail = true;
        }

        // 4. If everything passed, drop a single PASSED check so
        //    audits see "BINDING ran and approved this set".
        if (!anyHardFail) {
            report.add(ValidationCheck.builder()
                    .rule("binding-checks-passed")
                    .passed(true)
                    .message("all subgoals evidence-tied or speculative; "
                            + "all criteria covered; speculation ratio "
                            + String.format(java.util.Locale.ROOT, "%.2f", ratio) + " within bound "
                            + state.getMaxSpeculativeRatio())
                    .build());
        }

        state.setValidationReport(report);

        if (anyHardFail) {
            // Build a corrective hint for the LLM. Multiple checks
            // can fail at once — surface up to 3 to avoid prompt
            // bloat; the validationReport keeps the full list for
            // audit.
            String hint = buildHint(report);
            state.setPendingRecovery(RecoveryRequest.builder()
                    .fromPhase(ArchitectStatus.BINDING)
                    .toPhase(ArchitectStatus.DECOMPOSING)
                    .reason(firstFail.getRule())
                    .hint(hint)
                    .offendingId(firstFail.getOffendingId())
                    .build());

            appendIteration(state,
                    state.getSubgoals().size() + " subgoals, "
                            + state.getAcceptanceCriteria().size() + " criteria",
                    "FAILED — " + countFailed(report) + " violation(s); "
                            + "rollback to DECOMPOSING",
                    PhaseIteration.IterationOutcome.REQUESTED_RECOVERY);

            log.info("Slartibartfast id='{}' BINDING failed — {} violations, "
                            + "requesting DECOMPOSING re-run",
                    process.getId(), countFailed(report));
        } else {
            appendIteration(state,
                    state.getSubgoals().size() + " subgoals, "
                            + state.getAcceptanceCriteria().size() + " criteria",
                    "passed — " + speculativeCount + " speculative ("
                            + String.format(java.util.Locale.ROOT, "%.2f", ratio) + ")",
                    PhaseIteration.IterationOutcome.PASSED);
        }
    }

    // ──────────────────── Helpers ────────────────────

    private static String buildHint(List<ValidationCheck> report) {
        StringBuilder sb = new StringBuilder();
        sb.append("BINDING validation rejected the previous decomposing ")
                .append("output. The following violations — address EVERY "
                        + "one:\n");
        int shown = 0;
        for (ValidationCheck v : report) {
            if (v.isPassed()) continue;
            if (shown >= 5) break;
            sb.append("- [").append(v.getRule()).append("] ")
                    .append(v.getMessage()).append("\n");
            shown++;
        }
        sb.append("\nEmit a corrected subgoals list in which every "
                + "subgoal either has at least one FACT/EXAMPLE "
                + "evidenceRef OR is speculative=true with a "
                + "rationale; every acceptanceCriterion is addressed "
                + "by at least one subgoal; and the speculative ratio "
                + "stays within the limit.");
        return sb.toString();
    }

    private static long countFailed(List<ValidationCheck> report) {
        return report.stream().filter(v -> !v.isPassed()).count();
    }

    private static <T> Set<String> collectIds(
            List<T> items, java.util.function.Function<T, String> idOf) {
        Set<String> ids = new HashSet<>(items.size());
        for (T item : items) ids.add(idOf.apply(item));
        return ids;
    }

    private static Claim findClaim(ArchitectState state, String id) {
        for (Claim c : state.getEvidenceClaims()) {
            if (id.equals(c.getId())) return c;
        }
        return null;
    }

    private static void appendIteration(
            ArchitectState state,
            String inputSummary,
            String outputSummary,
            PhaseIteration.IterationOutcome outcome) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == ArchitectStatus.BINDING).count() + 1;
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(PhaseIteration.builder()
                .iteration(attempt)
                .phase(ArchitectStatus.BINDING)
                .triggeredBy(attempt == 1 ? "initial" : "recovery")
                .inputSummary(inputSummary)
                .outputSummary(outputSummary)
                .outcome(outcome)
                .build());
        state.setIterations(log);
    }
}
