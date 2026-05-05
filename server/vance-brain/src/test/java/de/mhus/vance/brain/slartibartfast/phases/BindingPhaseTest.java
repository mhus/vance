package de.mhus.vance.brain.slartibartfast.phases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Claim;
import de.mhus.vance.api.slartibartfast.ClassificationKind;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.Subgoal;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BindingPhase}. Pure-logic gate — verifies
 * the six rule families (claim-ref / criterion-ref resolution,
 * evidence-or-speculative, FACT/EXAMPLE-tier, criterion coverage,
 * speculation bound) and the recovery-request shape.
 */
class BindingPhaseTest {

    private BindingPhase phase;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        phase = new BindingPhase();
        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("test-project");
        process.setSessionId("sess-1");
        ctx = mock(ThinkEngineContext.class);
    }

    @Test
    void wellFormedSubgoalsAndCoverage_passes() {
        ArchitectState state = stateWith(
                List.of(criterion("cr1"), criterion("cr2")),
                List.of(factClaim("cl1", "ev1"), factClaim("cl2", "ev1")),
                List.of(
                        evidenced("sg1", List.of("cl1"), List.of("cr1")),
                        evidenced("sg2", List.of("cl2"), List.of("cr2"))));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNull();
        assertThat(state.getValidationReport())
                .extracting(ValidationCheck::isPassed)
                .containsOnly(true);
        assertThat(state.getIterations())
                .filteredOn(it -> it.getPhase() == ArchitectStatus.BINDING)
                .hasSize(1);
        assertThat(state.getIterations().get(0).getOutcome())
                .isEqualTo(PhaseIteration.IterationOutcome.PASSED);
    }

    @Test
    void danglingClaimRef_triggersRecoveryToDecomposing() {
        ArchitectState state = stateWith(
                List.of(criterion("cr1")),
                List.of(factClaim("cl1", "ev1")),
                List.of(evidenced("sg1", List.of("cl-ghost"), List.of("cr1"))));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getPendingRecovery().getFromPhase())
                .isEqualTo(ArchitectStatus.BINDING);
        assertThat(state.getPendingRecovery().getToPhase())
                .isEqualTo(ArchitectStatus.DECOMPOSING);
        assertThat(state.getPendingRecovery().getReason())
                .isEqualTo(BindingPhase.RULE_NO_DANGLING_CLAIM_REF);
        assertThat(state.getPendingRecovery().getOffendingId()).isEqualTo("sg1");
        assertThat(state.getPendingRecovery().getHint())
                .contains("non-existent claim 'cl-ghost'");
        assertThat(state.getIterations().get(0).getOutcome())
                .isEqualTo(PhaseIteration.IterationOutcome.REQUESTED_RECOVERY);
    }

    @Test
    void danglingCriterionRef_triggersRecovery() {
        ArchitectState state = stateWith(
                List.of(criterion("cr1")),
                List.of(factClaim("cl1", "ev1")),
                List.of(evidenced("sg1", List.of("cl1"), List.of("cr-ghost"))));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getValidationReport())
                .extracting(ValidationCheck::getRule)
                .contains(BindingPhase.RULE_NO_DANGLING_CRITERION_REF);
    }

    @Test
    void missingEvidenceOrSpeculative_triggersRecovery() {
        ArchitectState state = stateWith(
                List.of(criterion("cr1")),
                List.of(factClaim("cl1", "ev1")),
                List.of(Subgoal.builder()
                        .id("sg1")
                        .goal("orphan")
                        .evidenceRefs(List.of())   // empty
                        .criterionRefs(List.of("cr1"))
                        .speculative(false)        // and not speculative
                        .build()));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getPendingRecovery().getReason())
                .isEqualTo(BindingPhase.RULE_EVIDENCE_OR_SPECULATIVE);
    }

    @Test
    void opinionOnlyEvidence_isRejected_for_nonSpeculativeSubgoal() {
        ArchitectState state = stateWith(
                List.of(criterion("cr1")),
                List.of(opinionClaim("cl1", "ev1")),
                List.of(evidenced("sg1", List.of("cl1"), List.of("cr1"))));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getValidationReport())
                .extracting(ValidationCheck::getRule)
                .contains(BindingPhase.RULE_NOT_OPINION_ONLY);
    }

    @Test
    void factPlusOpinion_passes_oneFactSuffices() {
        ArchitectState state = stateWith(
                List.of(criterion("cr1")),
                List.of(factClaim("cl1", "ev1"), opinionClaim("cl2", "ev1")),
                List.of(evidenced("sg1", List.of("cl1", "cl2"), List.of("cr1"))));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNull();
    }

    @Test
    void exampleClassificationCounts_asFirmEvidence() {
        ArchitectState state = stateWith(
                List.of(criterion("cr1")),
                List.of(exampleClaim("cl1", "ev1")),
                List.of(evidenced("sg1", List.of("cl1"), List.of("cr1"))));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNull();
    }

    @Test
    void uncoveredCriterion_triggersRecovery() {
        ArchitectState state = stateWith(
                List.of(criterion("cr1"), criterion("cr2-orphan")),
                List.of(factClaim("cl1", "ev1")),
                List.of(evidenced("sg1", List.of("cl1"), List.of("cr1"))));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getValidationReport())
                .filteredOn(v -> BindingPhase.RULE_CRITERION_COVERAGE.equals(v.getRule()))
                .extracting(ValidationCheck::getOffendingId)
                .containsExactly("cr2-orphan");
    }

    @Test
    void speculationOverBound_triggersRecovery() {
        // 2 of 3 speculative = 0.67 > default 0.30
        ArchitectState state = stateWith(
                List.of(criterion("cr1")),
                List.of(factClaim("cl1", "ev1")),
                List.of(
                        evidenced("sg1", List.of("cl1"), List.of("cr1")),
                        speculative("sg2", List.of("cr1"), "guess one"),
                        speculative("sg3", List.of("cr1"), "guess two")));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getValidationReport())
                .extracting(ValidationCheck::getRule)
                .contains(BindingPhase.RULE_SPECULATION_BOUND);
        assertThat(state.getValidationReport())
                .filteredOn(v -> BindingPhase.RULE_SPECULATION_BOUND.equals(v.getRule()))
                .extracting(ValidationCheck::getMessage)
                .first().asString().contains("0.67")
                .contains("0.3");
    }

    @Test
    void speculationWithinBound_passes() {
        // 1 of 4 speculative = 0.25 ≤ default 0.30
        ArchitectState state = stateWith(
                List.of(criterion("cr1")),
                List.of(factClaim("cl1", "ev1")),
                List.of(
                        evidenced("sg1", List.of("cl1"), List.of("cr1")),
                        evidenced("sg2", List.of("cl1"), List.of("cr1")),
                        evidenced("sg3", List.of("cl1"), List.of("cr1")),
                        speculative("sg4", List.of("cr1"), "edge case")));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNull();
    }

    @Test
    void speculativeMissingRationale_triggersRecovery() {
        ArchitectState state = stateWith(
                List.of(criterion("cr1")),
                List.of(),
                List.of(Subgoal.builder()
                        .id("sg1")
                        .goal("orphan")
                        .evidenceRefs(List.of())
                        .criterionRefs(List.of("cr1"))
                        .speculative(true)
                        .speculationRationale(null)   // missing
                        .build()));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getValidationReport())
                .extracting(ValidationCheck::getRule)
                .contains(BindingPhase.RULE_EVIDENCE_OR_SPECULATIVE);
    }

    @Test
    void hintLists_eachFailingRule() {
        ArchitectState state = stateWith(
                List.of(criterion("cr1"), criterion("cr2-orphan")),
                List.of(opinionClaim("cl1", "ev1")),
                List.of(evidenced("sg1", List.of("cl-ghost"), List.of("cr1"))));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        String hint = state.getPendingRecovery().getHint();
        assertThat(hint).contains(BindingPhase.RULE_NO_DANGLING_CLAIM_REF);
        assertThat(hint).contains(BindingPhase.RULE_CRITERION_COVERAGE);
    }

    @Test
    void emptySubgoals_setsFailureReason() {
        ArchitectState state = stateWith(
                List.of(criterion("cr1")),
                List.of(factClaim("cl1", "ev1")),
                List.of());

        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason())
                .contains("BINDING entered with empty subgoals");
    }

    // ──────────────────── builders ────────────────────

    private static ArchitectState stateWith(
            List<Criterion> criteria,
            List<Claim> claims,
            List<Subgoal> subgoals) {
        return ArchitectState.builder()
                .runId("run1")
                .acceptanceCriteria(new ArrayList<>(criteria))
                .evidenceClaims(new ArrayList<>(claims))
                .subgoals(new ArrayList<>(subgoals))
                .maxSpeculativeRatio(0.30)
                .build();
    }

    private static Criterion criterion(String id) {
        return Criterion.builder()
                .id(id).text("text " + id)
                .origin(CriterionOrigin.USER_STATED)
                .confidence(1.0).build();
    }

    private static Claim factClaim(String id, String sourceId) {
        return Claim.builder()
                .id(id).sourceId(sourceId)
                .text("fact " + id)
                .classification(ClassificationKind.FACT).build();
    }

    private static Claim opinionClaim(String id, String sourceId) {
        return Claim.builder()
                .id(id).sourceId(sourceId)
                .text("opinion " + id)
                .classification(ClassificationKind.OPINION)
                .classificationRationaleId("rt-x").build();
    }

    private static Claim exampleClaim(String id, String sourceId) {
        return Claim.builder()
                .id(id).sourceId(sourceId)
                .text("example " + id)
                .classification(ClassificationKind.EXAMPLE)
                .classificationRationaleId("rt-x").build();
    }

    private static Subgoal evidenced(
            String id, List<String> evidenceRefs, List<String> criterionRefs) {
        return Subgoal.builder()
                .id(id).goal("goal " + id)
                .evidenceRefs(evidenceRefs)
                .criterionRefs(criterionRefs)
                .speculative(false).build();
    }

    private static Subgoal speculative(
            String id, List<String> criterionRefs, String rationale) {
        return Subgoal.builder()
                .id(id).goal("speculative " + id)
                .evidenceRefs(List.of())
                .criterionRefs(criterionRefs)
                .speculative(true)
                .speculationRationale(rationale).build();
    }
}
