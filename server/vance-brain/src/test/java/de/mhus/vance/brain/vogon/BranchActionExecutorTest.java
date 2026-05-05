package de.mhus.vance.brain.vogon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.vogon.BranchAction;
import de.mhus.vance.api.vogon.LoopSpec;
import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import de.mhus.vance.api.vogon.StrategyState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link BranchActionExecutor}. Verifies that the
 * action vocabulary mutates {@link StrategyState} as documented and
 * returns the expected terminal {@link BranchActionExecutor.Result}.
 */
class BranchActionExecutorTest {

    @Test
    void setFlagPersistsBooleanByDefault() {
        StrategyState state = new StrategyState();
        BranchActionExecutor.Result r = BranchActionExecutor.execute(
                strategy(), state, List.of(new BranchAction.SetFlag("approved")));

        assertThat(r.kind()).isEqualTo(BranchActionExecutor.ResultKind.CONTINUE);
        assertThat(state.getFlags()).containsEntry("approved", Boolean.TRUE);
    }

    @Test
    void setFlagWithExplicitValue() {
        StrategyState state = new StrategyState();
        BranchActionExecutor.execute(strategy(), state,
                List.of(new BranchAction.SetFlag("score_band", "high")));

        assertThat(state.getFlags()).containsEntry("score_band", "high");
    }

    @Test
    void setFlagsBatchSetsAllToTrue() {
        StrategyState state = new StrategyState();
        BranchActionExecutor.execute(strategy(), state,
                List.of(new BranchAction.SetFlags(List.of("a", "b", "c"))));

        assertThat(state.getFlags())
                .containsEntry("a", Boolean.TRUE)
                .containsEntry("b", Boolean.TRUE)
                .containsEntry("c", Boolean.TRUE);
    }

    @Test
    void notifyParentStashesPendingNotification() {
        StrategyState state = new StrategyState();
        BranchActionExecutor.execute(strategy(), state, List.of(
                new BranchAction.NotifyParent("BLOCKED", "halted on review")));

        assertThat(state.getFlags()).containsKey("__pendingNotifyParent__");
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>)
                state.getFlags().get("__pendingNotifyParent__");
        assertThat(pending).containsEntry("type", "BLOCKED")
                .containsEntry("summary", "halted on review");
    }

    @Test
    void escalateToReturnsTerminalEscalation() {
        StrategyState state = new StrategyState();
        BranchActionExecutor.Result r = BranchActionExecutor.execute(
                strategy(), state, List.of(new BranchAction.SetFlag("rejected_hard"),
                        new BranchAction.EscalateTo("deeper-review",
                                Map.of("reason", "too short"))));

        assertThat(r.kind()).isEqualTo(BranchActionExecutor.ResultKind.ESCALATED);
        assertThat(r.detail()).isEqualTo("deeper-review");
        assertThat(r.escalationParams()).containsEntry("reason", "too short");
        // Earlier setFlag still landed.
        assertThat(state.getFlags()).containsEntry("rejected_hard", Boolean.TRUE);
    }

    @Test
    void jumpToPhaseUpdatesPathAndReturnsJumped() {
        StrategySpec strategy = strategy(workerPhase("a"), workerPhase("b"));
        StrategyState state = stateAt("a");

        BranchActionExecutor.Result r = BranchActionExecutor.execute(
                strategy, state, List.of(new BranchAction.JumpToPhase("b")));

        assertThat(r.kind()).isEqualTo(BranchActionExecutor.ResultKind.JUMPED);
        assertThat(r.detail()).isEqualTo("b");
        assertThat(state.getCurrentPhasePath()).containsExactly("b");
    }

    @Test
    void jumpToPhaseFailsForUnknownPhase() {
        StrategySpec strategy = strategy(workerPhase("a"));
        StrategyState state = stateAt("a");

        assertThatThrownBy(() -> BranchActionExecutor.execute(
                strategy, state, List.of(new BranchAction.JumpToPhase("unknown"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown phase");
    }

    @Test
    void exitLoopReturnsExitOutcomeAndStopsList() {
        StrategyState state = new StrategyState();
        BranchActionExecutor.Result r = BranchActionExecutor.execute(
                strategy(), state, List.of(
                        new BranchAction.SetFlag("approved"),
                        new BranchAction.ExitLoop(BranchAction.ExitOutcome.OK),
                        // Should never run — list aborts at terminal.
                        new BranchAction.SetFlag("ghost")));

        assertThat(r.kind()).isEqualTo(BranchActionExecutor.ResultKind.EXIT_LOOP);
        assertThat(r.exitOutcome()).isEqualTo(BranchAction.ExitOutcome.OK);
        assertThat(state.getFlags()).containsEntry("approved", Boolean.TRUE);
        assertThat(state.getFlags()).doesNotContainKey("ghost");
    }

    @Test
    void exitStrategyTerminal() {
        StrategyState state = new StrategyState();
        BranchActionExecutor.Result r = BranchActionExecutor.execute(
                strategy(), state, List.of(
                        new BranchAction.ExitStrategy(BranchAction.ExitOutcome.FAIL)));

        assertThat(r.kind()).isEqualTo(BranchActionExecutor.ResultKind.EXIT_STRATEGY);
        assertThat(r.exitOutcome()).isEqualTo(BranchAction.ExitOutcome.FAIL);
    }

    @Test
    void pauseTerminal() {
        StrategyState state = new StrategyState();
        BranchActionExecutor.Result r = BranchActionExecutor.execute(
                strategy(), state, List.of(new BranchAction.Pause("needs human")));

        assertThat(r.kind()).isEqualTo(BranchActionExecutor.ResultKind.PAUSED);
        assertThat(r.detail()).isEqualTo("needs human");
    }

    @Test
    void emptyListIsContinue() {
        BranchActionExecutor.Result r = BranchActionExecutor.execute(
                strategy(), new StrategyState(), List.of());
        assertThat(r.kind()).isEqualTo(BranchActionExecutor.ResultKind.CONTINUE);
    }

    @Test
    void jumpToSubPhaseInsideLoopIsAccepted() {
        // jumpToPhase points at a sub-phase. The path resets to a single
        // segment with that name; the engine resolves it in the next turn.
        // (Today the resolver only descends top-level loops; jumping into
        // a sub-phase by bare name only works if the sub name is unique
        // and the resolver finds it. The executor accepts the jump as
        // long as the name exists somewhere in the strategy.)
        PhaseSpec writer = workerPhase("writer");
        PhaseSpec lector = workerPhase("lector");
        PhaseSpec loop = PhaseSpec.builder()
                .name("iter")
                .loop(LoopSpec.builder()
                        .maxIterations(3)
                        .subPhases(new ArrayList<>(Arrays.asList(writer, lector)))
                        .build())
                .build();
        StrategySpec strategy = strategy(loop);

        StrategyState state = stateAt("iter");
        BranchActionExecutor.Result r = BranchActionExecutor.execute(
                strategy, state, List.of(new BranchAction.JumpToPhase("lector")));

        assertThat(r.kind()).isEqualTo(BranchActionExecutor.ResultKind.JUMPED);
    }

    // ──────────────── helpers ────────────────

    private static StrategySpec strategy(PhaseSpec... phases) {
        return StrategySpec.builder()
                .name("t")
                .phases(new ArrayList<>(Arrays.asList(phases)))
                .build();
    }

    private static PhaseSpec workerPhase(String name) {
        return PhaseSpec.builder().name(name).worker("dummy").build();
    }

    private static StrategyState stateAt(String... segments) {
        StrategyState s = new StrategyState();
        for (String seg : segments) s.getCurrentPhasePath().add(seg);
        return s;
    }
}
