package de.mhus.vance.brain.slartibartfast.phases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import de.mhus.vance.api.slartibartfast.FramedGoal;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfirmingPhase}. Pure logic — no LLM, no
 * tool calls — verify the threshold partition + audit-append
 * behaviour of the M3.1 minimal CONFIRMING.
 */
class ConfirmingPhaseTest {

    private ConfirmingPhase phase;
    private ThinkProcessDocument process;
    private ThinkEngineContext ctx;

    @BeforeEach
    void setUp() {
        phase = new ConfirmingPhase();
        process = new ThinkProcessDocument();
        process.setId("proc-1");
        process.setTenantId("acme");
        process.setProjectId("test-project");
        process.setSessionId("sess-1");
        ctx = mock(ThinkEngineContext.class);
    }

    @Test
    void allStatedPlusHighConfAssumed_passThrough() {
        ArchitectState state = ArchitectState.builder()
                .runId("run1")
                .confirmationThreshold(0.85)
                .goal(FramedGoal.builder()
                        .framed("essay")
                        .statedCriteria(List.of(
                                stated("cr1", "essay must exist"),
                                stated("cr2", "in adams style")))
                        .assumedCriteria(List.of(
                                assumed("cr3", "german language", 0.95, "rt1"),
                                assumed("cr4", "saved as document", 0.90, "rt2")))
                        .build())
                .build();

        phase.execute(state, process, ctx);

        assertThat(state.getAcceptanceCriteria())
                .extracting(Criterion::getId)
                .containsExactly("cr1", "cr2", "cr3", "cr4");
        assertThat(state.getValidationReport())
                .filteredOn(c -> "low-confidence-criterion-dropped".equals(c.getRule()))
                .isEmpty();
    }

    @Test
    void lowConfAssumed_droppedAndAudited() {
        ArchitectState state = ArchitectState.builder()
                .runId("run1")
                .confirmationThreshold(0.85)
                .goal(FramedGoal.builder()
                        .framed("essay")
                        .statedCriteria(List.of(stated("cr1", "essay must exist")))
                        .assumedCriteria(List.of(
                                assumed("cr2", "high-conf inference", 0.95, "rt1"),
                                assumed("cr3", "borderline guess", 0.60, "rt2"),
                                assumed("cr4", "weak guess", 0.30, "rt3")))
                        .build())
                .build();

        phase.execute(state, process, ctx);

        assertThat(state.getAcceptanceCriteria())
                .extracting(Criterion::getId)
                .containsExactly("cr1", "cr2");
        assertThat(state.getValidationReport())
                .filteredOn(c -> "low-confidence-criterion-dropped".equals(c.getRule()))
                .extracting(ValidationCheck::getOffendingId)
                .containsExactly("cr3", "cr4");
    }

    @Test
    void userConfirmedOriginPassesThrough_evenBelowThreshold() {
        // M6 will set USER_CONFIRMED on criteria that the user
        // accepted via inbox dialog. Once flipped, the entry must
        // pass regardless of the recorded confidence number.
        ArchitectState state = ArchitectState.builder()
                .runId("run1")
                .confirmationThreshold(0.85)
                .goal(FramedGoal.builder()
                        .framed("essay")
                        .statedCriteria(List.of())
                        .assumedCriteria(List.of(Criterion.builder()
                                .id("cr1").text("user-confirmed weak guess")
                                .origin(CriterionOrigin.USER_CONFIRMED)
                                .confidence(0.40)
                                .rationaleId("rt1")
                                .build()))
                        .build())
                .build();

        phase.execute(state, process, ctx);

        assertThat(state.getAcceptanceCriteria())
                .extracting(Criterion::getId)
                .containsExactly("cr1");
    }

    @Test
    void emptyAssumed_yieldsAcceptedEqualsStated() {
        ArchitectState state = ArchitectState.builder()
                .runId("run1")
                .confirmationThreshold(0.85)
                .goal(FramedGoal.builder()
                        .framed("essay")
                        .statedCriteria(List.of(
                                stated("cr1", "a"),
                                stated("cr2", "b")))
                        .assumedCriteria(List.of())
                        .build())
                .build();

        phase.execute(state, process, ctx);

        assertThat(state.getAcceptanceCriteria()).hasSize(2);
        assertThat(state.getValidationReport())
                .filteredOn(c -> "low-confidence-criterion-dropped".equals(c.getRule()))
                .isEmpty();
    }

    @Test
    void missingGoal_failsWithFailureReason() {
        ArchitectState state = ArchitectState.builder()
                .runId("run1")
                .goal(null)
                .build();

        phase.execute(state, process, ctx);

        assertThat(state.getFailureReason())
                .contains("CONFIRMING entered without a FramedGoal");
        assertThat(state.getAcceptanceCriteria()).isEmpty();
    }

    @Test
    void appendsExactlyOneIterationPerExecute() {
        ArchitectState state = ArchitectState.builder()
                .runId("run1")
                .confirmationThreshold(0.85)
                .goal(FramedGoal.builder()
                        .framed("essay")
                        .statedCriteria(List.of(stated("cr1", "x")))
                        .assumedCriteria(List.of())
                        .build())
                .build();

        phase.execute(state, process, ctx);
        phase.execute(state, process, ctx);

        assertThat(state.getIterations())
                .filteredOn(it -> it.getPhase() == ArchitectStatus.CONFIRMING)
                .extracting(PhaseIteration::getIteration)
                .containsExactly(1, 2);
        assertThat(state.getIterations())
                .filteredOn(it -> it.getPhase() == ArchitectStatus.CONFIRMING)
                .extracting(PhaseIteration::getTriggeredBy)
                .containsExactly("initial", "recovery");
    }

    @Test
    void reExecute_rebuildsAcceptanceCriteriaFromGoalNotAccumulates() {
        // Recovery rollback to CONFIRMING must produce a fresh
        // working set, not pile onto a prior pass.
        ArchitectState state = ArchitectState.builder()
                .runId("run1")
                .confirmationThreshold(0.85)
                .goal(FramedGoal.builder()
                        .framed("essay")
                        .statedCriteria(List.of(stated("cr1", "x")))
                        .assumedCriteria(List.of())
                        .build())
                .build();

        phase.execute(state, process, ctx);
        assertThat(state.getAcceptanceCriteria()).hasSize(1);

        // Mutate the goal as if FRAMING re-prompted with a new
        // shape, then re-run CONFIRMING.
        state.getGoal().setStatedCriteria(List.of(
                stated("cr2", "different"), stated("cr3", "another")));
        phase.execute(state, process, ctx);

        assertThat(state.getAcceptanceCriteria())
                .extracting(Criterion::getId)
                .containsExactly("cr2", "cr3");
    }

    private static Criterion stated(String id, String text) {
        return Criterion.builder()
                .id(id).text(text)
                .origin(CriterionOrigin.USER_STATED)
                .confidence(1.0)
                .build();
    }

    private static Criterion assumed(
            String id, String text, double conf, String rationaleId) {
        return Criterion.builder()
                .id(id).text(text)
                .origin(CriterionOrigin.INFERRED_CONVENTION)
                .confidence(conf)
                .rationaleId(rationaleId)
                .build();
    }
}
