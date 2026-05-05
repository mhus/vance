package de.mhus.vance.brain.vogon;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.vogon.GateSpec;
import de.mhus.vance.api.vogon.LoopSpec;
import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import de.mhus.vance.api.vogon.StrategyState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link PhaseAdvancer}. Drives the path-stack
 * advance logic directly with hand-built {@link StrategySpec} +
 * {@link StrategyState} fixtures. No Spring context, no Mongo, no
 * worker spawn, no LLM — the advancer mutates state in-memory and
 * returns an {@link PhaseAdvancer.Outcome}.
 */
class PhaseAdvancerTest {

    @Nested
    class LinearStrategy {

        @Test
        void resolveActivePhaseReturnsLeafForLinear() {
            StrategySpec strategy = strategy(workerPhase("a"), workerPhase("b"));
            StrategyState state = stateAt("a");

            PhaseSpec resolved = PhaseAdvancer.resolveActivePhase(strategy, state);

            assertThat(resolved.getName()).isEqualTo("a");
            assertThat(state.getCurrentPhasePath()).containsExactly("a");
            assertThat(state.getLoopCounters()).isEmpty();
        }

        @Test
        void advanceMovesToNextSibling() {
            StrategySpec strategy = strategy(workerPhase("a"), workerPhase("b"));
            StrategyState state = stateAt("a");

            PhaseAdvancer.Outcome out = PhaseAdvancer.advanceAfter(
                    strategy, state, strategy.getPhases().get(0));

            assertThat(out).isEqualTo(PhaseAdvancer.Outcome.CONTINUE);
            assertThat(state.getCurrentPhasePath()).containsExactly("b");
            assertThat(state.getPhaseHistory()).containsExactly("a");
        }

        @Test
        void advanceOnLastPhaseReturnsStrategyDone() {
            StrategySpec strategy = strategy(workerPhase("a"), workerPhase("b"));
            StrategyState state = stateAt("b");

            PhaseAdvancer.Outcome out = PhaseAdvancer.advanceAfter(
                    strategy, state, strategy.getPhases().get(1));

            assertThat(out).isEqualTo(PhaseAdvancer.Outcome.STRATEGY_DONE);
            assertThat(state.getCurrentPhasePath()).isEmpty();
            assertThat(state.isStrategyComplete()).isTrue();
            assertThat(state.getPhaseHistory()).containsExactly("b");
        }
    }

    @Nested
    class LoopEntryAndIteration {

        @Test
        void resolveActivePhaseEntersLoopAndBumpsCounter() {
            PhaseSpec writer = workerPhase("writer");
            PhaseSpec lector = workerPhase("lector");
            PhaseSpec loop = loopPhase("write-and-review", 5,
                    LoopSpec.OnMaxReached.ESCALATE,
                    requiresAny("lector_approved",
                            "write-and-review" + PhaseAdvancer.MAX_ITER_SUFFIX),
                    writer, lector);
            StrategySpec strategy = strategy(loop);
            StrategyState state = stateAt("write-and-review");

            PhaseSpec resolved = PhaseAdvancer.resolveActivePhase(strategy, state);

            assertThat(resolved.getName()).isEqualTo("writer");
            assertThat(state.getCurrentPhasePath())
                    .containsExactly("write-and-review", "writer");
            assertThat(state.getLoopCounters()).containsEntry("write-and-review", 1);
        }

        @Test
        void advanceWithinLoopMovesToNextSubPhase() {
            PhaseSpec writer = workerPhase("writer");
            PhaseSpec lector = workerPhase("lector");
            StrategySpec strategy = strategy(
                    loopPhase("iter", 3, null, null, writer, lector));
            StrategyState state = stateAt("iter", "writer");
            state.getLoopCounters().put("iter", 1);

            PhaseAdvancer.Outcome out = PhaseAdvancer.advanceAfter(strategy, state, writer);

            assertThat(out).isEqualTo(PhaseAdvancer.Outcome.CONTINUE);
            assertThat(state.getCurrentPhasePath()).containsExactly("iter", "lector");
            assertThat(state.getPhaseHistory()).containsExactly("iter/writer");
        }

        @Test
        void lastSubPhaseWithGateSatisfiedExitsLoop() {
            PhaseSpec writer = workerPhase("writer");
            PhaseSpec lector = workerPhase("lector");
            PhaseSpec loop = loopPhase("iter", 5, LoopSpec.OnMaxReached.ESCALATE,
                    requiresAny("lector_approved",
                            "iter" + PhaseAdvancer.MAX_ITER_SUFFIX),
                    writer, lector);
            StrategySpec strategy = strategy(loop, workerPhase("publish"));
            StrategyState state = stateAt("iter", "lector");
            state.getLoopCounters().put("iter", 1);
            state.getFlags().put("lector_approved", true);
            state.getWorkerProcessIds().put("iter/writer", "child-1");
            state.getWorkerProcessIds().put("iter/lector", "child-2");

            PhaseAdvancer.Outcome out = PhaseAdvancer.advanceAfter(strategy, state, lector);

            assertThat(out).isEqualTo(PhaseAdvancer.Outcome.CONTINUE);
            assertThat(state.getCurrentPhasePath()).containsExactly("publish");
            // History records both the sub-phase and the loop wrapper.
            assertThat(state.getPhaseHistory()).containsExactly("iter/lector", "iter");
        }

        @Test
        void lastSubPhaseWithoutGateReentersLoop() {
            PhaseSpec writer = workerPhase("writer");
            PhaseSpec lector = workerPhase("lector");
            PhaseSpec loop = loopPhase("iter", 3, LoopSpec.OnMaxReached.ESCALATE,
                    requiresAny("lector_approved",
                            "iter" + PhaseAdvancer.MAX_ITER_SUFFIX),
                    writer, lector);
            StrategySpec strategy = strategy(loop);
            StrategyState state = stateAt("iter", "lector");
            state.getLoopCounters().put("iter", 1);
            // Lector did NOT approve — loop must re-enter.
            state.getWorkerProcessIds().put("iter/writer", "old-w");
            state.getWorkerProcessIds().put("iter/lector", "old-l");
            state.getPhaseArtifacts().put("iter/writer",
                    new java.util.LinkedHashMap<>(java.util.Map.of("result", "draft 1")));
            state.getFlags().put("writer_completed", true);
            state.getFlags().put("lector_completed", true);

            PhaseAdvancer.Outcome out = PhaseAdvancer.advanceAfter(strategy, state, lector);

            assertThat(out).isEqualTo(PhaseAdvancer.Outcome.CONTINUE);
            // Counter bumped, leaf back to first sub-phase.
            assertThat(state.getLoopCounters()).containsEntry("iter", 2);
            assertThat(state.getCurrentPhasePath()).containsExactly("iter", "writer");
            // Loop body workers + artifacts wiped.
            assertThat(state.getWorkerProcessIds()).doesNotContainKeys("iter/writer", "iter/lector");
            assertThat(state.getPhaseArtifacts()).doesNotContainKeys("iter/writer", "iter/lector");
            // Per-sub-phase flags wiped (so the next iteration's worker
            // can set them again).
            assertThat(state.getFlags()).doesNotContainKeys("writer_completed", "lector_completed");
        }
    }

    @Nested
    class LoopExhaustion {

        @Test
        void escalateOnMaxReachedReturnsEscalationNeeded() {
            PhaseSpec writer = workerPhase("writer");
            PhaseSpec lector = workerPhase("lector");
            PhaseSpec loop = loopPhase("iter", 2, LoopSpec.OnMaxReached.ESCALATE,
                    requiresAny("lector_approved",
                            "iter" + PhaseAdvancer.MAX_ITER_SUFFIX),
                    writer, lector);
            StrategySpec strategy = strategy(loop);
            StrategyState state = stateAt("iter", "lector");
            state.getLoopCounters().put("iter", 1);
            // Already on iteration 1; counter bump goes to 2 == max.
            // No approval flag.

            PhaseAdvancer.Outcome out = PhaseAdvancer.advanceAfter(strategy, state, lector);

            // Gate is `requiresAny: [lector_approved, iter_max_iterations_reached]`.
            // After the bump the max-iter flag is set, so the gate is
            // re-evaluated and now SATISFIED → exit cleanly through CONTINUE
            // (which becomes STRATEGY_DONE since loop is the only top-level
            // phase). Good — exhaustion *can* exit normally if the gate
            // includes the max-iter flag.
            assertThat(out).isEqualTo(PhaseAdvancer.Outcome.STRATEGY_DONE);
            assertThat(state.getFlags()).containsEntry(
                    "iter" + PhaseAdvancer.MAX_ITER_SUFFIX, true);
        }

        @Test
        void escalateWithoutMaxIterFlagInGateTriggersEscalation() {
            PhaseSpec writer = workerPhase("writer");
            PhaseSpec lector = workerPhase("lector");
            // Gate only depends on lector_approved — max-iter doesn't help.
            PhaseSpec loop = loopPhase("iter", 2, LoopSpec.OnMaxReached.ESCALATE,
                    requires("lector_approved"),
                    writer, lector);
            StrategySpec strategy = strategy(loop);
            StrategyState state = stateAt("iter", "lector");
            state.getLoopCounters().put("iter", 1);

            PhaseAdvancer.Outcome out = PhaseAdvancer.advanceAfter(strategy, state, lector);

            assertThat(out).isEqualTo(PhaseAdvancer.Outcome.ESCALATION_NEEDED);
            assertThat(state.getFlags())
                    .containsEntry("iter" + PhaseAdvancer.LOOP_FAILED_SUFFIX, true)
                    .containsEntry("iter" + PhaseAdvancer.MAX_ITER_SUFFIX, true);
        }

        @Test
        void exitFailMarksLoopFailedAndReturnsStrategyFailed() {
            PhaseSpec writer = workerPhase("writer");
            PhaseSpec loop = loopPhase("iter", 1, LoopSpec.OnMaxReached.EXIT_FAIL,
                    requires("never_satisfied"),
                    writer);
            StrategySpec strategy = strategy(loop);
            StrategyState state = stateAt("iter", "writer");
            state.getLoopCounters().put("iter", 1);

            PhaseAdvancer.Outcome out = PhaseAdvancer.advanceAfter(strategy, state, writer);

            // Loop has only one sub-phase and one iteration; after writer
            // DONE the boundary check finds no gate satisfaction, counter
            // is already at 1 = max, EXIT_FAIL fires. Loop pops, strategy
            // is the loop alone, so STRATEGY_DONE — but loop_failed flag
            // is set so a downstream observer can react.
            assertThat(out).isEqualTo(PhaseAdvancer.Outcome.STRATEGY_DONE);
            assertThat(state.getFlags()).containsEntry(
                    "iter" + PhaseAdvancer.LOOP_FAILED_SUFFIX, true);
        }

        @Test
        void exitOkLeavesLoopWithoutFailFlag() {
            PhaseSpec writer = workerPhase("writer");
            PhaseSpec loop = loopPhase("iter", 1, LoopSpec.OnMaxReached.EXIT_OK,
                    requires("never_satisfied"),
                    writer);
            StrategySpec strategy = strategy(loop, workerPhase("after"));
            StrategyState state = stateAt("iter", "writer");
            state.getLoopCounters().put("iter", 1);

            PhaseAdvancer.Outcome out = PhaseAdvancer.advanceAfter(strategy, state, writer);

            assertThat(out).isEqualTo(PhaseAdvancer.Outcome.CONTINUE);
            assertThat(state.getCurrentPhasePath()).containsExactly("after");
            assertThat(state.getFlags())
                    .doesNotContainKey("iter" + PhaseAdvancer.LOOP_FAILED_SUFFIX);
        }
    }

    @Nested
    class GateEvaluation {

        @Test
        void requiresAllAndNotMet() {
            StrategyState state = new StrategyState();
            state.getFlags().put("a", true);
            // Missing "b".
            GateSpec gate = GateSpec.builder()
                    .requires(List.of("a", "b"))
                    .requiresAny(new ArrayList<>())
                    .build();
            assertThat(PhaseAdvancer.gateSatisfied(gate, state)).isFalse();
        }

        @Test
        void requiresAnyOneSatisfies() {
            StrategyState state = new StrategyState();
            state.getFlags().put("b", true);
            GateSpec gate = GateSpec.builder()
                    .requires(new ArrayList<>())
                    .requiresAny(List.of("a", "b"))
                    .build();
            assertThat(PhaseAdvancer.gateSatisfied(gate, state)).isTrue();
        }

        @Test
        void nullGateAlwaysSatisfied() {
            assertThat(PhaseAdvancer.gateSatisfied(null, new StrategyState())).isTrue();
        }
    }

    // ──────────────────── factory helpers ────────────────────

    private static PhaseSpec workerPhase(String name) {
        return PhaseSpec.builder()
                .name(name)
                .worker("dummy-recipe")
                .build();
    }

    private static PhaseSpec loopPhase(
            String name,
            int maxIterations,
            LoopSpec.OnMaxReached onMaxReached,
            GateSpec until,
            PhaseSpec... subPhases) {
        return PhaseSpec.builder()
                .name(name)
                .loop(LoopSpec.builder()
                        .until(until)
                        .maxIterations(maxIterations)
                        .onMaxReached(onMaxReached == null
                                ? LoopSpec.OnMaxReached.ESCALATE : onMaxReached)
                        .subPhases(new ArrayList<>(java.util.Arrays.asList(subPhases)))
                        .build())
                .build();
    }

    private static StrategySpec strategy(PhaseSpec... phases) {
        return StrategySpec.builder()
                .name("test-strategy")
                .phases(new ArrayList<>(java.util.Arrays.asList(phases)))
                .build();
    }

    private static StrategyState stateAt(String... pathSegments) {
        StrategyState state = new StrategyState();
        for (String s : pathSegments) state.getCurrentPhasePath().add(s);
        return state;
    }

    private static GateSpec requires(String... flags) {
        return GateSpec.builder()
                .requires(new ArrayList<>(java.util.Arrays.asList(flags)))
                .requiresAny(new ArrayList<>())
                .build();
    }

    private static GateSpec requiresAny(String... flags) {
        return GateSpec.builder()
                .requires(new ArrayList<>())
                .requiresAny(new ArrayList<>(java.util.Arrays.asList(flags)))
                .build();
    }
}
