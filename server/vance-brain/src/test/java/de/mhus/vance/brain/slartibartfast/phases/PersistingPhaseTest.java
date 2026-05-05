package de.mhus.vance.brain.slartibartfast.phases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.Subgoal;
import de.mhus.vance.api.slartibartfast.TerminationRationale;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link PersistingPhase}. Verifies the recipe is
 * written to the {@code recipes/_slart/<runId>/} bucket via
 * {@link DocumentService}, the {@link TerminationRationale} is
 * built correctly, and the path is recorded on the state.
 */
class PersistingPhaseTest {

    private DocumentService documentService;
    private PersistingPhase phase;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        phase = new PersistingPhase(documentService, JsonMapper.builder().build());

        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("test-project");
        process.setSessionId("sess-1");
        ctx = mock(ThinkEngineContext.class);

        // Default: no document exists at any path → falls into
        // createText branch.
        when(documentService.findByPath(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void writesRecipeAndAuditToSlartBucket() {
        ArchitectState state = stateWithMinimalRecipe("3a4f7c91", "essay-pipeline");

        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason()).isNull();
        assertThat(state.getPersistedRecipePath())
                .isEqualTo("recipes/_slart/3a4f7c91/essay-pipeline.yaml");

        ArgumentCaptor<String> pathCap = ArgumentCaptor.forClass(String.class);
        verify(documentService, times(2)).createText(
                eq("acme"), eq("test-project"), pathCap.capture(),
                any(), any(), any(), any());
        assertThat(pathCap.getAllValues())
                .containsExactly(
                        "recipes/_slart/3a4f7c91/essay-pipeline.yaml",
                        "recipes/_slart/3a4f7c91/audit.json");
    }

    @Test
    void existingRecipeIsUpdated_notRecreated() {
        DocumentDocument existing = new DocumentDocument();
        existing.setId("docid-recipe");
        existing.setPath("recipes/_slart/3a4f7c91/essay-pipeline.yaml");
        when(documentService.findByPath("acme", "test-project",
                "recipes/_slart/3a4f7c91/essay-pipeline.yaml"))
                .thenReturn(Optional.of(existing));

        ArchitectState state = stateWithMinimalRecipe("3a4f7c91", "essay-pipeline");
        phase.execute(state, process, ctx);

        verify(documentService, never()).createText(
                any(), any(),
                eq("recipes/_slart/3a4f7c91/essay-pipeline.yaml"),
                any(), any(), any(), any());
        verify(documentService, atLeastOnce()).update(
                eq("docid-recipe"), any(), any(), any(), any());
    }

    @Test
    void terminationRationale_buildsFromState() {
        ArchitectState state = stateWithMinimalRecipe("ab12", "x");
        // Add 2 stated, 1 assumed (taken-for-granted), 1 USER_CONFIRMED criterion
        state.setAcceptanceCriteria(new java.util.ArrayList<>(List.of(
                stated("cr1"), stated("cr2"),
                inferred("cr3", CriterionOrigin.INFERRED_CONVENTION),
                inferred("cr4", CriterionOrigin.USER_CONFIRMED))));
        // All 4 criteria covered: cr1 by sg1+sg3, cr2 by sg2,
        // cr3 by sg2, cr4 by sg2.
        state.setSubgoals(new java.util.ArrayList<>(List.of(
                evidenced("sg1", List.of("cr1")),
                evidenced("sg2", List.of("cr2", "cr3", "cr4")),
                speculative("sg3", List.of("cr1")))));
        // 1 of 3 speculative.
        state.setValidationReport(new java.util.ArrayList<>(List.of(
                pass("recipe-yaml-parses"),
                pass("subgoal-claim-ref-resolves"))));
        state.setRecoveryCount(1);
        state.setIterations(new java.util.ArrayList<>(List.of(
                iter(ArchitectStatus.FRAMING),
                iter(ArchitectStatus.DECOMPOSING),
                iter(ArchitectStatus.BINDING))));

        phase.execute(state, process, ctx);

        TerminationRationale tr = state.getTerminationRationale();
        assertThat(tr).isNotNull();
        assertThat(tr.getStatedCriteriaSatisfied()).containsExactly("cr1", "cr2");
        assertThat(tr.getAssumedCriteriaTakenForGranted()).containsExactly("cr3");
        assertThat(tr.getAssumedCriteriaUserConfirmed()).containsExactly("cr4");
        assertThat(tr.getCriterionCoverage())
                .containsEntry("cr1", List.of("sg1", "sg3"))
                .containsEntry("cr2", List.of("sg2"))
                .containsEntry("cr3", List.of("sg2"))
                .containsEntry("cr4", List.of("sg2"));
        // 1 of 3 speculative → evidenceCoverage = 2/3 ≈ 0.667.
        assertThat(tr.getEvidenceCoverage()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
        // TerminationRationale is built BEFORE the PERSISTING
        // iteration is appended, so it sees the 3 pre-existing
        // entries only. The PERSISTING entry is added afterwards
        // for audit but doesn't show up in the rationale's count.
        assertThat(tr.getIterationCount()).isEqualTo(3);
        assertThat(tr.getRecoveryEvents()).isEqualTo(1);
        assertThat(tr.getFinalConfidence()).isEqualTo(0.85);
        assertThat(tr.getPassedChecks())
                .containsExactly("recipe-yaml-parses", "subgoal-claim-ref-resolves");
    }

    @Test
    void missingRecipeDraft_setsFailureReason() {
        ArchitectState state = ArchitectState.builder()
                .runId("run1").proposedRecipe(null).build();

        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason())
                .contains("PERSISTING entered without a proposedRecipe");
    }

    @Test
    void emptyRunId_setsFailureReason() {
        ArchitectState state = stateWithMinimalRecipe("", "x");

        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason())
                .contains("PERSISTING entered with empty runId");
    }

    @Test
    void recipeWriteThrowing_marksFailure() {
        when(documentService.findByPath(any(), any(), any()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("disk full"))
                .when(documentService).createText(
                        any(), any(),
                        eq("recipes/_slart/3a4f7c91/x.yaml"),
                        any(), any(), any(), any());

        ArchitectState state = stateWithMinimalRecipe("3a4f7c91", "x");
        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason())
                .contains("disk full");
        assertThat(state.getIterations())
                .filteredOn(it -> it.getPhase() == ArchitectStatus.PERSISTING)
                .extracting(PhaseIteration::getOutcome)
                .containsExactly(PhaseIteration.IterationOutcome.FAILED);
    }

    @Test
    void auditWriteFailing_doesNotFailRun() {
        // Recipe write succeeds, audit write throws — phase still
        // marks PASSED because the recipe is the productive output.
        when(documentService.findByPath(any(), any(),
                eq("recipes/_slart/3a4f7c91/x.yaml")))
                .thenReturn(Optional.empty());
        when(documentService.findByPath(any(), any(),
                eq("recipes/_slart/3a4f7c91/audit.json")))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("audit-fail"))
                .when(documentService).createText(
                        any(), any(),
                        eq("recipes/_slart/3a4f7c91/audit.json"),
                        any(), any(), any(), any());

        ArchitectState state = stateWithMinimalRecipe("3a4f7c91", "x");
        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason()).isNull();
        assertThat(state.getPersistedRecipePath())
                .isEqualTo("recipes/_slart/3a4f7c91/x.yaml");
        assertThat(state.getIterations())
                .filteredOn(it -> it.getPhase() == ArchitectStatus.PERSISTING)
                .extracting(PhaseIteration::getOutcome)
                .containsExactly(PhaseIteration.IterationOutcome.PASSED);
    }

    // ──────────────────── builders ────────────────────

    private static ArchitectState stateWithMinimalRecipe(String runId, String name) {
        return ArchitectState.builder()
                .runId(runId)
                .outputSchemaType(OutputSchemaType.VOGON_STRATEGY)
                .acceptanceCriteria(new java.util.ArrayList<>(List.of(stated("cr1"))))
                .subgoals(new java.util.ArrayList<>(List.of(
                        evidenced("sg1", List.of("cr1")))))
                .proposedRecipe(RecipeDraft.builder()
                        .name(name)
                        .outputSchemaType(OutputSchemaType.VOGON_STRATEGY)
                        .yaml("name: " + name + "\nengine: vogon\nparams:\n  strategyPlanYaml: |\n    name: x\n")
                        .justifications(Map.of("name", "sg1"))
                        .confidence(0.85)
                        .build())
                .build();
    }

    private static Criterion stated(String id) {
        return Criterion.builder()
                .id(id).text("text " + id)
                .origin(CriterionOrigin.USER_STATED)
                .confidence(1.0).build();
    }

    private static Criterion inferred(String id, CriterionOrigin origin) {
        return Criterion.builder()
                .id(id).text("text " + id)
                .origin(origin)
                .confidence(0.9)
                .rationaleId("rt1").build();
    }

    private static Subgoal evidenced(String id, List<String> criterionRefs) {
        return Subgoal.builder()
                .id(id).goal("goal " + id)
                .evidenceRefs(List.of("cl1"))
                .criterionRefs(criterionRefs)
                .speculative(false).build();
    }

    private static Subgoal speculative(String id, List<String> criterionRefs) {
        return Subgoal.builder()
                .id(id).goal("goal " + id)
                .evidenceRefs(List.of())
                .criterionRefs(criterionRefs)
                .speculative(true)
                .speculationRationale("test").build();
    }

    private static ValidationCheck pass(String rule) {
        return ValidationCheck.builder()
                .rule(rule).passed(true).message("ok").build();
    }

    private static PhaseIteration iter(ArchitectStatus phase) {
        return PhaseIteration.builder()
                .iteration(1).phase(phase)
                .triggeredBy("initial")
                .outcome(PhaseIteration.IterationOutcome.PASSED).build();
    }
}
