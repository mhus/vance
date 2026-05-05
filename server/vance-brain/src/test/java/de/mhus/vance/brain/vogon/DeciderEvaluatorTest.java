package de.mhus.vance.brain.vogon;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.vogon.BranchAction;
import de.mhus.vance.api.vogon.DeciderCase;
import de.mhus.vance.api.vogon.DeciderSpec;
import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import de.mhus.vance.api.vogon.StrategyState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link DeciderEvaluator}. Exercises token
 * matching (binary + 3-option), case-insensitivity, word-boundary
 * handling, and branch-action dispatch — no Spring, no LLM.
 */
class DeciderEvaluatorTest {

    @Nested
    class TokenMatch {

        @Test
        void binaryYesMatched() {
            String chosen = DeciderEvaluator.matchToken("Yes.", List.of("yes", "no"));
            assertThat(chosen).isEqualTo("yes");
        }

        @Test
        void binaryNoMatchedDespiteSurroundingProse() {
            String chosen = DeciderEvaluator.matchToken(
                    "After thinking it through, no — that won't work.",
                    List.of("yes", "no"));
            assertThat(chosen).isEqualTo("no");
        }

        @Test
        void firstOccurrenceWinsAcrossOptions() {
            // "no" appears first, "yes" appears second → no wins.
            String chosen = DeciderEvaluator.matchToken(
                    "no, actually yes if you squint",
                    List.of("yes", "no"));
            assertThat(chosen).isEqualTo("no");
        }

        @Test
        void substringInLargerWordRejected() {
            // "yes" is a substring of "yesterday" — must NOT match.
            String chosen = DeciderEvaluator.matchToken(
                    "Yesterday I considered it. Maybe.",
                    List.of("yes", "no"));
            assertThat(chosen).isNull();
        }

        @Test
        void caseInsensitiveAtWordBoundary() {
            String chosen = DeciderEvaluator.matchToken(
                    "VERDICT: AMBIGUOUS",
                    List.of("unambiguous", "ambiguous", "contradictory"));
            assertThat(chosen).isEqualTo("ambiguous");
        }

        @Test
        void noMatchReturnsNull() {
            String chosen = DeciderEvaluator.matchToken(
                    "I cannot say either way",
                    List.of("yes", "no"));
            assertThat(chosen).isNull();
        }
    }

    @Nested
    class Evaluate {

        @Test
        void yesMatchSetsFlagAndExitsLoop() {
            DeciderSpec spec = binaryDecider();
            StrategyState state = new StrategyState();
            StrategySpec strategy = strategy(workerPhase("ask"));

            DeciderEvaluator.Result r = DeciderEvaluator.evaluate(
                    strategy, state, workerPhase("ask"), "ask", spec, "yes");

            assertThat(r.outcome()).isEqualTo(DeciderEvaluator.Outcome.COMPLETED);
            assertThat(r.chosenToken()).isEqualTo("yes");
            assertThat(state.getFlags()).containsEntry("should_continue", "yes")
                    .containsEntry("keep_going", Boolean.TRUE);
            // Decider artifact stashed under phaseKey.
            assertThat(state.getPhaseArtifacts())
                    .containsKey("ask");
            assertThat(r.branchResult().kind())
                    .isEqualTo(BranchActionExecutor.ResultKind.CONTINUE);
        }

        @Test
        void noMatchTriggersExitLoop() {
            DeciderSpec spec = binaryDecider();
            StrategyState state = new StrategyState();
            StrategySpec strategy = strategy(workerPhase("ask"));

            DeciderEvaluator.Result r = DeciderEvaluator.evaluate(
                    strategy, state, workerPhase("ask"), "ask", spec, "no thanks.");

            assertThat(r.outcome()).isEqualTo(DeciderEvaluator.Outcome.COMPLETED);
            assertThat(r.chosenToken()).isEqualTo("no");
            assertThat(r.branchResult().kind())
                    .isEqualTo(BranchActionExecutor.ResultKind.EXIT_LOOP);
            assertThat(r.branchResult().exitOutcome())
                    .isEqualTo(BranchAction.ExitOutcome.OK);
        }

        @Test
        void threeOptionDeciderEachCaseDispatches() {
            DeciderSpec spec = triageDecider();
            StrategyState state = new StrategyState();
            StrategySpec strategy = strategy(workerPhase("classify"));

            DeciderEvaluator.Result amb = DeciderEvaluator.evaluate(
                    strategy, state, workerPhase("classify"), "classify",
                    spec, "VERDICT: AMBIGUOUS — tighten the outline.");
            assertThat(amb.chosenToken()).isEqualTo("ambiguous");
            assertThat(amb.branchResult().kind())
                    .isEqualTo(BranchActionExecutor.ResultKind.JUMPED);
            assertThat(amb.branchResult().detail()).isEqualTo("clarify");

            // Reset state for second sub-test.
            state = new StrategyState();
            DeciderEvaluator.Result contra = DeciderEvaluator.evaluate(
                    strategy, state, workerPhase("classify"), "classify",
                    spec, "contradictory");
            assertThat(contra.chosenToken()).isEqualTo("contradictory");
            assertThat(contra.branchResult().kind())
                    .isEqualTo(BranchActionExecutor.ResultKind.ESCALATED);
            assertThat(contra.branchResult().detail()).isEqualTo("outline-rebuild");
        }

        @Test
        void emptyReplyReturnsNeedsCorrection() {
            DeciderEvaluator.Result r = DeciderEvaluator.evaluate(
                    strategy(), new StrategyState(),
                    workerPhase("ask"), "ask", binaryDecider(), "");

            assertThat(r.outcome()).isEqualTo(DeciderEvaluator.Outcome.NEEDS_CORRECTION);
            assertThat(r.correctionHint()).contains("yes").contains("no");
        }

        @Test
        void noOptionInReplyReturnsNeedsCorrection() {
            DeciderEvaluator.Result r = DeciderEvaluator.evaluate(
                    strategy(), new StrategyState(),
                    workerPhase("ask"), "ask", binaryDecider(),
                    "I cannot decide either way");

            assertThat(r.outcome()).isEqualTo(DeciderEvaluator.Outcome.NEEDS_CORRECTION);
        }

        @Test
        void unhandledOptionPersistsFlagButRunsNoBranch() {
            // Decider declares options [a, b, c] but only cases for a and b.
            // If the LLM picks c, we persist the flag but no branch fires.
            DeciderSpec spec = DeciderSpec.builder()
                    .options(new ArrayList<>(List.of("a", "b", "c")))
                    .storeAs("kind")
                    .cases(new ArrayList<>(List.of(
                            DeciderCase.builder()
                                    .when("a")
                                    .doActions(List.of(new BranchAction.SetFlag("got_a")))
                                    .build(),
                            DeciderCase.builder()
                                    .when("b")
                                    .doActions(List.of(new BranchAction.SetFlag("got_b")))
                                    .build())))
                    .build();
            StrategyState state = new StrategyState();
            DeciderEvaluator.Result r = DeciderEvaluator.evaluate(
                    strategy(), state, workerPhase("p"), "p", spec, "c");

            assertThat(r.outcome()).isEqualTo(DeciderEvaluator.Outcome.COMPLETED);
            assertThat(r.chosenToken()).isEqualTo("c");
            assertThat(state.getFlags()).containsEntry("kind", "c")
                    .doesNotContainKey("got_a")
                    .doesNotContainKey("got_b");
            assertThat(r.branchResult().kind())
                    .isEqualTo(BranchActionExecutor.ResultKind.CONTINUE);
        }
    }

    // ──────────────── helpers ────────────────

    private static DeciderSpec binaryDecider() {
        return DeciderSpec.builder()
                .options(new ArrayList<>(List.of("yes", "no")))
                .storeAs("should_continue")
                .cases(new ArrayList<>(List.of(
                        DeciderCase.builder()
                                .when("yes")
                                .doActions(List.of(new BranchAction.SetFlag("keep_going")))
                                .build(),
                        DeciderCase.builder()
                                .when("no")
                                .doActions(List.of(new BranchAction.ExitLoop(
                                        BranchAction.ExitOutcome.OK)))
                                .build())))
                .build();
    }

    private static DeciderSpec triageDecider() {
        return DeciderSpec.builder()
                .options(new ArrayList<>(List.of(
                        "unambiguous", "ambiguous", "contradictory")))
                .storeAs("outline_clarity")
                .cases(new ArrayList<>(List.of(
                        DeciderCase.builder()
                                .when("unambiguous")
                                .doActions(List.of(new BranchAction.SetFlag("outline_ok")))
                                .build(),
                        DeciderCase.builder()
                                .when("ambiguous")
                                .doActions(List.of(new BranchAction.JumpToPhase("clarify")))
                                .build(),
                        DeciderCase.builder()
                                .when("contradictory")
                                .doActions(List.of(new BranchAction.EscalateTo(
                                        "outline-rebuild")))
                                .build())))
                .build();
    }

    private static PhaseSpec workerPhase(String name) {
        // For triage tests we need the jumpTo target to exist in the
        // strategy — provide a "clarify" phase too.
        return PhaseSpec.builder().name(name).worker("dummy").build();
    }

    private static StrategySpec strategy(PhaseSpec... phases) {
        List<PhaseSpec> all = new ArrayList<>(Arrays.asList(phases));
        // Ensure jumpTo / escalateTo targets used in the tests exist as
        // top-level phases — BranchActionExecutor checks phase existence.
        all.add(PhaseSpec.builder().name("clarify").worker("dummy").build());
        return StrategySpec.builder()
                .name("t")
                .phases(all)
                .build();
    }
}
