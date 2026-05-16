package de.mhus.vance.brain.slartibartfast.phases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.Subgoal;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExecutionValidatingPhase}. Verifies the
 * regex path extraction, the per-path existence + size check,
 * and the recovery-request shape on failure. No LLM, no Mongo —
 * DocumentService is mocked.
 */
class ExecutionValidatingPhaseTest {

    private DocumentService documentService;
    private ExecutionValidatingPhase phase;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        // ContentValidatingPhase needs LLM infrastructure we don't
        // wire in unit tests — mock it so it always reports
        // "skipped" (returns false), leaving the structural check
        // as the only active gate.
        ContentValidatingPhase contentValidating = mock(ContentValidatingPhase.class);
        phase = new ExecutionValidatingPhase(documentService, contentValidating);
        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("test-project");
        process.setSessionId("sess-1");
        ctx = mock(ThinkEngineContext.class);
    }

    @Test
    void allExpectedArtifactsPresent_passes() {
        ArchitectState state = stateWith(List.of(
                sg("sg1", "Draft 'essay/chapters/01-intro.md' in German.", false),
                sg("sg2", "Draft 'essay/chapters/02-body.md' in German.", false),
                sg("sg3", "Assemble 'essay/final-essay.md' from chapters.", false)));

        stubDoc("essay/chapters/01-intro.md", 1500);
        stubDoc("essay/chapters/02-body.md", 2000);
        stubDoc("essay/final-essay.md", 4000);

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNull();
        assertThat(state.getValidationReport())
                .extracting("rule", "passed")
                .contains(
                        org.assertj.core.groups.Tuple.tuple(
                                ExecutionValidatingPhase.RULE_ARTIFACTS_PRESENT,
                                true));
        assertThat(state.getIterations())
                .extracting(PhaseIteration::getPhase,
                        PhaseIteration::getOutcome)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(
                                ArchitectStatus.EXECUTION_VALIDATING,
                                PhaseIteration.IterationOutcome.PASSED));
    }

    @Test
    void missingArtifact_setsRecoveryToProposing() {
        ArchitectState state = stateWith(List.of(
                sg("sg1", "Write 'essay/chapters/01-intro.md'.", false),
                sg("sg2", "Write 'essay/chapters/02-body.md'.", false)));

        stubDoc("essay/chapters/01-intro.md", 1500);
        when(documentService.findByPath(any(), any(),
                        eq("essay/chapters/02-body.md")))
                .thenReturn(Optional.empty());

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery())
                .isNotNull()
                .extracting("fromPhase", "toPhase", "reason")
                .containsExactly(
                        ArchitectStatus.EXECUTION_VALIDATING,
                        ArchitectStatus.PROPOSING,
                        ExecutionValidatingPhase.RULE_ARTIFACTS_PRESENT);
        assertThat(state.getPendingRecovery().getHint())
                .contains("essay/chapters/02-body.md")
                .contains("Missing entirely");
        assertThat(state.getIterations())
                .extracting(PhaseIteration::getOutcome)
                .contains(PhaseIteration.IterationOutcome.REQUESTED_RECOVERY);
    }

    @Test
    void underSizedArtifact_triggersRecovery() {
        ArchitectState state = stateWith(List.of(
                sg("sg1", "Write 'essay/chapters/01-intro.md'.", false)));

        // Stub returns a doc with content shorter than MIN_ARTIFACT_CHARS.
        stubDoc("essay/chapters/01-intro.md", 50);

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNotNull();
        assertThat(state.getPendingRecovery().getHint())
                .contains("Below minimum content")
                .contains("essay/chapters/01-intro.md");
    }

    @Test
    void speculativeSubgoals_areSkipped() {
        ArchitectState state = stateWith(List.of(
                sg("sg1", "Write 'essay/chapters/01-intro.md'.", false),
                sg("sg2", "Speculate about 'essay/chapters/99-maybe.md'.",
                        /*speculative*/ true)));

        stubDoc("essay/chapters/01-intro.md", 1500);
        // sg2's path is in a speculative subgoal — must not be queried.

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNull();
    }

    @Test
    void noParseablePaths_passesVacuously() {
        ArchitectState state = stateWith(List.of(
                sg("sg1", "Send a notification to the user.", false),
                sg("sg2", "Mark the project as complete.", false)));

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNull();
        assertThat(state.getIterations())
                .extracting(PhaseIteration::getOutcome)
                .contains(PhaseIteration.IterationOutcome.PASSED);
    }

    @Test
    void emptySubgoals_passesVacuously() {
        ArchitectState state = ArchitectState.builder()
                .status(ArchitectStatus.EXECUTION_VALIDATING)
                .build();

        phase.execute(state, process, ctx);

        assertThat(state.getPendingRecovery()).isNull();
        assertThat(state.getValidationReport()).isEmpty();
    }

    // ──────────────────── helpers ────────────────────

    private void stubDoc(String path, int charCount) {
        DocumentDocument doc = new DocumentDocument();
        doc.setPath(path);
        doc.setInlineText("x".repeat(charCount));
        when(documentService.findByPath(eq("acme"), eq("test-project"), eq(path)))
                .thenReturn(Optional.of(doc));
    }

    private static Subgoal sg(String id, String goal, boolean speculative) {
        return Subgoal.builder()
                .id(id)
                .goal(goal)
                .speculative(speculative)
                .speculationRationale(speculative ? "test-spec" : null)
                .build();
    }

    private static ArchitectState stateWith(List<Subgoal> subgoals) {
        return ArchitectState.builder()
                .status(ArchitectStatus.EXECUTION_VALIDATING)
                .subgoals(new java.util.ArrayList<>(subgoals))
                .build();
    }
}
