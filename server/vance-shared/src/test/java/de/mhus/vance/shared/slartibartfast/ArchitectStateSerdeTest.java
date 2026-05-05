package de.mhus.vance.shared.slartibartfast;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Claim;
import de.mhus.vance.api.slartibartfast.ClassificationKind;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import de.mhus.vance.api.slartibartfast.EvidenceSource;
import de.mhus.vance.api.slartibartfast.EvidenceType;
import de.mhus.vance.api.slartibartfast.FramedGoal;
import de.mhus.vance.api.slartibartfast.LlmCallRecord;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.Rationale;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.RecoveryRequest;
import de.mhus.vance.api.slartibartfast.Subgoal;
import de.mhus.vance.api.slartibartfast.TerminationRationale;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the Slartibartfast wire-contract types serialise to JSON
 * and round-trip back without losing data. {@link ArchitectState}
 * is persisted on
 * {@code ThinkProcessDocument.engineParams.architectState} via
 * Jackson — this test pins the contract so a careless DTO change
 * doesn't silently corrupt resumed processes.
 *
 * <p>Covers B0 (rationale + iteration + recovery + LLM-call record
 * + termination rationale) and B1 (criterion origin / confidence /
 * rationaleId, FramedGoal stated/assumed split, CONFIRMING status).
 */
class ArchitectStateSerdeTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void emptyState_roundtrips_withDefaults() throws Exception {
        ArchitectState empty = ArchitectState.builder()
                .runId("3a4f7c91")
                .userDescription("write me a story")
                .build();

        String json = mapper.writeValueAsString(empty);
        ArchitectState back = mapper.readValue(json, ArchitectState.class);

        assertThat(back).isEqualTo(empty);
        assertThat(back.getStatus()).isEqualTo(ArchitectStatus.READY);
        assertThat(back.getOutputSchemaType()).isEqualTo(OutputSchemaType.VOGON_STRATEGY);
        assertThat(back.getMaxSpeculativeRatio()).isEqualTo(0.30);
        assertThat(back.getConfirmationThreshold()).isEqualTo(0.85);
        assertThat(back.getMaxRecoveries()).isEqualTo(5);
        assertThat(back.isAuditLlmCalls()).isTrue();
        assertThat(back.getRationales()).isEmpty();
        assertThat(back.getIterations()).isEmpty();
        assertThat(back.getLlmCallRecords()).isEmpty();
        assertThat(back.getPendingRecovery()).isNull();
        assertThat(back.getTerminationRationale()).isNull();
    }

    @Test
    void fullyPopulatedState_roundtrips_preservingAuditChain() throws Exception {
        Rationale convRat = Rationale.builder()
                .id("rt1").text("standard convention: written content gets persisted")
                .inferredAt(ArchitectStatus.FRAMING).build();
        Rationale gatherRat = Rationale.builder()
                .id("rt2").text("manual chosen because user-text mentions adams style")
                .sourceRefs(List.of("ev1"))
                .inferredAt(ArchitectStatus.GATHERING).build();
        Rationale classRat = Rationale.builder()
                .id("rt3").text("'absurd lists' is a measurable Adams pattern")
                .sourceRefs(List.of("ev1"))
                .inferredAt(ArchitectStatus.CLASSIFYING).build();
        Rationale decompRat = Rationale.builder()
                .id("rt4").text("decomposed into outline+chapters+aggregate per kit pattern")
                .inferredAt(ArchitectStatus.DECOMPOSING).build();
        Rationale shapeRat = Rationale.builder()
                .id("rt5").text("marvin-recipe needed because tree-decomposition required")
                .inferredAt(ArchitectStatus.PROPOSING).build();

        FramedGoal goal = FramedGoal.builder()
                .framed("Produce a Douglas-Adams style essay in 3 chapters")
                .sourceUserText("schreib mir eine geschichte adams style")
                .statedCriteria(List.of(
                        Criterion.builder()
                                .id("cr1").text("style is Douglas Adams")
                                .origin(CriterionOrigin.USER_STATED)
                                .confidence(1.0).build()))
                .assumedCriteria(List.of(
                        Criterion.builder()
                                .id("cr2").text("essay is persisted as a document")
                                .origin(CriterionOrigin.INFERRED_CONVENTION)
                                .confidence(0.95).rationaleId("rt1").build(),
                        Criterion.builder()
                                .id("cr3").text("essay has 3-5 chapters")
                                .origin(CriterionOrigin.INFERRED_DOMAIN)
                                .confidence(0.55).rationaleId("rt1").build()))
                .build();

        EvidenceSource manual = EvidenceSource.builder()
                .id("ev1").type(EvidenceType.MANUAL)
                .path("manuals/essay/STYLE.md")
                .content("Adams uses absurd lists.\nDry narrator voice.")
                .gatheringRationaleId("rt2").build();

        Claim factClaim = Claim.builder()
                .id("cl1").sourceId("ev1")
                .text("Adams uses absurd lists")
                .classification(ClassificationKind.FACT)
                .quote("Adams uses absurd lists.")
                .classificationRationaleId("rt3").build();

        Subgoal evidenced = Subgoal.builder()
                .id("sg1").goal("Outline with 3 chapters")
                .evidenceRefs(List.of("cl1"))
                .criterionRefs(List.of("cr1"))
                .speculative(false).build();

        Map<String, String> just = new LinkedHashMap<>();
        just.put("params.allowedSubTaskRecipes", "sg1");

        RecipeDraft draft = RecipeDraft.builder()
                .name("essay-pipeline")
                .outputSchemaType(OutputSchemaType.MARVIN_RECIPE)
                .yaml("name: essay-pipeline\nengine: marvin\n")
                .justifications(just)
                .confidence(0.83)
                .warnings("Aggregator step is speculative")
                .shapeRationaleId("rt5").build();

        PhaseIteration framingIter = PhaseIteration.builder()
                .iteration(1).phase(ArchitectStatus.FRAMING)
                .triggeredBy("initial")
                .inputSummary("user-description=schreib mir eine geschichte")
                .outputSummary("1 stated, 2 assumed")
                .outcome(PhaseIteration.IterationOutcome.PASSED)
                .llmCallRecordId("llm1").build();
        PhaseIteration decompIter1 = PhaseIteration.builder()
                .iteration(1).phase(ArchitectStatus.DECOMPOSING)
                .triggeredBy("initial")
                .inputSummary("3 claims, 3 criteria")
                .outputSummary("4 subgoals, 0 speculative")
                .outcome(PhaseIteration.IterationOutcome.REQUESTED_RECOVERY)
                .llmCallRecordId("llm4").build();
        PhaseIteration decompIter2 = PhaseIteration.builder()
                .iteration(2).phase(ArchitectStatus.DECOMPOSING)
                .triggeredBy("speculation-bound")
                .inputSummary("3 claims, 3 criteria, hint=add-evidence-for-sg2")
                .outputSummary("4 subgoals, 0 speculative (sg2 now evidenced)")
                .outcome(PhaseIteration.IterationOutcome.PASSED)
                .llmCallRecordId("llm5").build();

        LlmCallRecord call = LlmCallRecord.builder()
                .id("llm5").phase(ArchitectStatus.DECOMPOSING).iteration(2)
                .promptHash("0xfeed")
                .promptPreview("[corrective] previous decomposition exceeded speculation-bound...")
                .response("{...subgoals: ...}")
                .modelAlias("gemini:gemini-2.5-flash")
                .durationMs(1234L).build();

        RecoveryRequest pending = RecoveryRequest.builder()
                .fromPhase(ArchitectStatus.VALIDATING)
                .toPhase(ArchitectStatus.PROPOSING)
                .reason("recipe-yaml-malformed")
                .hint("indent the params block correctly")
                .offendingId(null).build();

        TerminationRationale term = TerminationRationale.builder()
                .passedChecks(List.of("recipe-yaml-parses", "no-dangling-claim-refs"))
                .statedCriteriaSatisfied(List.of("cr1"))
                .assumedCriteriaTakenForGranted(List.of("cr2"))
                .assumedCriteriaUserConfirmed(List.of("cr3"))
                .evidenceCoverage(0.92)
                .iterationCount(11)
                .recoveryEvents(2)
                .finalConfidence(0.83).build();

        ArchitectState state = ArchitectState.builder()
                .runId("3a4f7c91")
                .userDescription("schreib mir eine geschichte adams style")
                .outputSchemaType(OutputSchemaType.MARVIN_RECIPE)
                .status(ArchitectStatus.VALIDATING)
                .goal(goal)
                .evidenceSources(List.of(manual))
                .evidenceClaims(List.of(factClaim))
                .subgoals(List.of(evidenced))
                .decompositionRationaleId("rt4")
                .proposedRecipe(draft)
                .rationales(List.of(convRat, gatherRat, classRat, decompRat, shapeRat))
                .iterations(List.of(framingIter, decompIter1, decompIter2))
                .llmCallRecords(List.of(call))
                .pendingRecovery(pending)
                .recoveryCount(2)
                .maxRecoveries(5)
                .confirmationThreshold(0.85)
                .maxSpeculativeRatio(0.30)
                .auditLlmCalls(true)
                .terminationRationale(term)
                .persistedRecipePath(null)
                .build();

        String json = mapper.writeValueAsString(state);
        ArchitectState back = mapper.readValue(json, ArchitectState.class);

        assertThat(back).isEqualTo(state);

        // spot-check the pieces most likely to break under
        // structural change
        assertThat(back.getGoal().getStatedCriteria()).hasSize(1);
        assertThat(back.getGoal().getAssumedCriteria()).hasSize(2);
        assertThat(back.getGoal().getAssumedCriteria().get(1).getOrigin())
                .isEqualTo(CriterionOrigin.INFERRED_DOMAIN);
        assertThat(back.getGoal().getAssumedCriteria().get(1).getConfidence())
                .isEqualTo(0.55);
        assertThat(back.getRationales()).hasSize(5);
        assertThat(back.getIterations()).hasSize(3);
        assertThat(back.getIterations().get(1).getOutcome())
                .isEqualTo(PhaseIteration.IterationOutcome.REQUESTED_RECOVERY);
        assertThat(back.getLlmCallRecords()).hasSize(1);
        assertThat(back.getPendingRecovery()).isNotNull();
        assertThat(back.getPendingRecovery().getToPhase())
                .isEqualTo(ArchitectStatus.PROPOSING);
        assertThat(back.getTerminationRationale()).isNotNull();
        assertThat(back.getTerminationRationale().getRecoveryEvents()).isEqualTo(2);
    }

    @Test
    void newStatusValues_serialiseAsTheirNames() throws Exception {
        ArchitectState state = ArchitectState.builder()
                .runId("test")
                .status(ArchitectStatus.CONFIRMING)
                .outputSchemaType(OutputSchemaType.MARVIN_RECIPE)
                .build();

        String json = mapper.writeValueAsString(state);

        assertThat(json).contains("\"status\":\"CONFIRMING\"");
        assertThat(json).contains("\"outputSchemaType\":\"MARVIN_RECIPE\"");
    }
}
