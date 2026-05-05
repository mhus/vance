package de.mhus.vance.brain.vogon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.vogon.BranchAction;
import de.mhus.vance.api.vogon.DeciderSpec;
import de.mhus.vance.api.vogon.LoopSpec;
import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.ScorerSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link StrategyResolver#parseStrategy(String, String)} —
 * exercises the YAML schema for loop / scorer / decider / branch-action
 * blocks (Phase A of the v2 Vogon work). No Spring, no Mongo, no LLM.
 */
class StrategyResolverYamlTest {

    @Nested
    class LinearBaseline {
        @Test
        void parsesPlainPhaseListUnchanged() {
            String yaml = """
                    name: linear
                    phases:
                      - name: planning
                        worker: analyze
                        workerInput: "Plan: ${params.goal}"
                        gate: { requires: [planning_completed] }
                      - name: review
                        worker: code-read
                        gate: { requires: [review_completed] }
                    """;
            StrategySpec spec = StrategyResolver.parseStrategy(yaml, "test:linear");

            assertThat(spec.getName()).isEqualTo("linear");
            assertThat(spec.getPhases()).hasSize(2);
            PhaseSpec planning = spec.getPhases().get(0);
            assertThat(planning.getWorker()).isEqualTo("analyze");
            assertThat(planning.getLoop()).isNull();
            assertThat(planning.getScorer()).isNull();
            assertThat(planning.getDecider()).isNull();
        }
    }

    @Nested
    class LoopBlock {
        @Test
        void parsesWriterLectorLoop() {
            String yaml = """
                    name: lector-loop
                    phases:
                      - name: write-and-review
                        loop:
                          maxIterations: 5
                          onMaxReached: escalate
                          until:
                            requiresAny:
                              - lector_approved
                              - write_and_review_max_iterations_reached
                          subPhases:
                            - name: writer
                              worker: creative-writer
                              gate: { requires: [writer_completed] }
                            - name: lector
                              worker: lector-scorer
                              gate: { requires: [lector_completed] }
                    """;
            StrategySpec spec = StrategyResolver.parseStrategy(yaml, "test:lector-loop");

            assertThat(spec.getPhases()).hasSize(1);
            LoopSpec loop = spec.getPhases().get(0).getLoop();
            assertThat(loop).isNotNull();
            assertThat(loop.getMaxIterations()).isEqualTo(5);
            assertThat(loop.getOnMaxReached()).isEqualTo(LoopSpec.OnMaxReached.ESCALATE);
            assertThat(loop.getSubPhases()).extracting(PhaseSpec::getName)
                    .containsExactly("writer", "lector");
            assertThat(loop.getUntil().getRequiresAny())
                    .containsExactlyInAnyOrder(
                            "lector_approved",
                            "write_and_review_max_iterations_reached");
        }

        @Test
        void rejectsLoopWithWorkerOnSamePhase() {
            String yaml = """
                    name: bad
                    phases:
                      - name: hybrid
                        worker: x
                        loop:
                          subPhases:
                            - name: inner
                              worker: y
                    """;
            assertThatThrownBy(() -> StrategyResolver.parseStrategy(yaml, "test:bad"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("loop-phase must not also declare");
        }

        @Test
        void rejectsNestedLoops() {
            String yaml = """
                    name: nested
                    phases:
                      - name: outer
                        loop:
                          subPhases:
                            - name: inner
                              loop:
                                subPhases:
                                  - name: deepest
                                    worker: x
                    """;
            assertThatThrownBy(() -> StrategyResolver.parseStrategy(yaml, "test:nested"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nested loops are not supported");
        }

        @Test
        void rejectsLoopWithEmptySubPhases() {
            String yaml = """
                    name: empty
                    phases:
                      - name: hollow
                        loop:
                          subPhases: []
                    """;
            assertThatThrownBy(() -> StrategyResolver.parseStrategy(yaml, "test:empty"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("subPhases must be a non-empty list");
        }

        @Test
        void rejectsZeroMaxIterations() {
            String yaml = """
                    name: bad
                    phases:
                      - name: l
                        loop:
                          maxIterations: 0
                          subPhases:
                            - name: w
                              worker: x
                    """;
            assertThatThrownBy(() -> StrategyResolver.parseStrategy(yaml, "test:bad"))
                    .hasMessageContaining("maxIterations must be >= 1");
        }
    }

    @Nested
    class ScorerBlock {
        @Test
        void parsesThreeCaseLectorScorer() {
            String yaml = """
                    name: scoring
                    phases:
                      - name: lector
                        worker: lector-recipe
                        scorer:
                          storeAs: lector
                          schema:
                            score: float
                            summary: string
                          cases:
                            - when: { scoreBelow: 0.2 }
                              do:
                                - setFlag: lector_rejected_hard
                                - escalateTo: { strategy: deeper-review }
                            - when: { scoreAtLeast: 0.7 }
                              do:
                                - setFlag: lector_approved
                                - exitLoop: ok
                            - when: { default: true }
                              do:
                                - setFlag: lector_revision_needed
                          maxCorrections: 3
                    """;
            StrategySpec spec = StrategyResolver.parseStrategy(yaml, "test:scoring");
            ScorerSpec scorer = spec.getPhases().get(0).getScorer();
            assertThat(scorer).isNotNull();
            assertThat(scorer.getStoreAs()).isEqualTo("lector");
            assertThat(scorer.getMaxCorrections()).isEqualTo(3);
            assertThat(scorer.getCases()).hasSize(3);

            // Case 1: scoreBelow 0.2 → setFlag + escalateTo (terminal).
            assertThat(scorer.getCases().get(0).getWhen().getScoreBelow()).isEqualTo(0.2);
            assertThat(scorer.getCases().get(0).getDoActions())
                    .hasSize(2)
                    .satisfies(actions -> {
                        assertThat(actions.get(0))
                                .isInstanceOfSatisfying(BranchAction.SetFlag.class, sf -> {
                                    assertThat(sf.name()).isEqualTo("lector_rejected_hard");
                                    assertThat(sf.value()).isEqualTo(Boolean.TRUE);
                                });
                        assertThat(actions.get(1))
                                .isInstanceOfSatisfying(BranchAction.EscalateTo.class, e -> {
                                    assertThat(e.strategy()).isEqualTo("deeper-review");
                                    assertThat(e.terminal()).isTrue();
                                });
                    });

            // Case 2: scoreAtLeast 0.7 → setFlag + exitLoop ok.
            assertThat(scorer.getCases().get(1).getWhen().getScoreAtLeast()).isEqualTo(0.7);
            BranchAction last = scorer.getCases().get(1).getDoActions().get(1);
            assertThat(last).isInstanceOfSatisfying(BranchAction.ExitLoop.class, el ->
                    assertThat(el.outcome()).isEqualTo(BranchAction.ExitOutcome.OK));

            // Case 3: default match.
            assertThat(scorer.getCases().get(2).getWhen().isDefaultMatch()).isTrue();
        }

        @Test
        void rejectsBothScoreAtLeastAndScoreBelow() {
            String yaml = """
                    name: bad
                    phases:
                      - name: p
                        worker: x
                        scorer:
                          storeAs: s
                          cases:
                            - when: { scoreAtLeast: 0.5, scoreBelow: 0.7 }
                              do: [{ setFlag: y }]
                    """;
            assertThatThrownBy(() -> StrategyResolver.parseStrategy(yaml, "test:bad"))
                    .hasMessageContaining("exactly one of");
        }

        @Test
        void rejectsDefaultMatchNotLast() {
            String yaml = """
                    name: bad
                    phases:
                      - name: p
                        worker: x
                        scorer:
                          storeAs: s
                          cases:
                            - when: { default: true }
                              do: [{ setFlag: a }]
                            - when: { scoreAtLeast: 0.5 }
                              do: [{ setFlag: b }]
                    """;
            assertThatThrownBy(() -> StrategyResolver.parseStrategy(yaml, "test:bad"))
                    .hasMessageContaining("default-match must be the last case");
        }

        @Test
        void rejectsUnreachableActionAfterTerminal() {
            String yaml = """
                    name: bad
                    phases:
                      - name: p
                        worker: x
                        scorer:
                          storeAs: s
                          cases:
                            - when: { scoreAtLeast: 0.5 }
                              do:
                                - exitLoop: ok
                                - setFlag: ghost
                    """;
            assertThatThrownBy(() -> StrategyResolver.parseStrategy(yaml, "test:bad"))
                    .hasMessageContaining("is terminal — actions after it are unreachable");
        }

        @Test
        void rejectsMixingScorerAndDecider() {
            String yaml = """
                    name: bad
                    phases:
                      - name: p
                        worker: x
                        scorer:
                          storeAs: s
                          cases:
                            - when: { scoreAtLeast: 0.5 }
                              do: [{ setFlag: a }]
                        decider:
                          storeAs: d
                          cases:
                            - when: yes
                              do: [{ setFlag: b }]
                    """;
            assertThatThrownBy(() -> StrategyResolver.parseStrategy(yaml, "test:bad"))
                    .hasMessageContaining("scorer and decider are mutually exclusive");
        }
    }

    @Nested
    class DeciderBlock {
        @Test
        void defaultsToYesNoWhenOptionsOmitted() {
            String yaml = """
                    name: decide
                    phases:
                      - name: ask
                        worker: classifier
                        decider:
                          storeAs: should_continue
                          cases:
                            - when: yes
                              do: [{ setFlag: keep_going }]
                            - when: no
                              do: [{ exitLoop: ok }]
                    """;
            DeciderSpec d = StrategyResolver.parseStrategy(yaml, "test:decide")
                    .getPhases().get(0).getDecider();
            assertThat(d.getOptions()).containsExactly("yes", "no");
            assertThat(d.getCases()).hasSize(2);
            assertThat(d.getCases().get(0).getWhen()).isEqualTo("yes");
            assertThat(d.getCases().get(1).getDoActions().get(0))
                    .isInstanceOf(BranchAction.ExitLoop.class);
        }

        @Test
        void parsesThreeOptionDecider() {
            String yaml = """
                    name: triage
                    phases:
                      - name: classify
                        worker: classifier
                        decider:
                          options: [unambiguous, ambiguous, contradictory]
                          storeAs: outline_clarity
                          cases:
                            - when: unambiguous
                              do: [{ setFlag: outline_ok }]
                            - when: ambiguous
                              do: [{ jumpToPhase: clarify }]
                            - when: contradictory
                              do: [{ escalateTo: outline-rebuild }]
                    """;
            DeciderSpec d = StrategyResolver.parseStrategy(yaml, "test:triage")
                    .getPhases().get(0).getDecider();
            assertThat(d.getOptions())
                    .containsExactly("unambiguous", "ambiguous", "contradictory");
            assertThat(d.getCases().get(2).getDoActions().get(0))
                    .isInstanceOfSatisfying(BranchAction.EscalateTo.class, e ->
                            assertThat(e.strategy()).isEqualTo("outline-rebuild"));
        }

        @Test
        void rejectsCaseWithUnknownOption() {
            String yaml = """
                    name: bad
                    phases:
                      - name: p
                        worker: x
                        decider:
                          options: [a, b]
                          storeAs: s
                          cases:
                            - when: c
                              do: [{ setFlag: ghost }]
                    """;
            assertThatThrownBy(() -> StrategyResolver.parseStrategy(yaml, "test:bad"))
                    .hasMessageContaining("must be one of options=");
        }
    }

    @Nested
    class BranchActionParsing {
        @Test
        void parsesAllNineActionFormsAcrossOneDoList() {
            String yaml = """
                    name: kitchen-sink
                    phases:
                      - name: p
                        worker: w
                        scorer:
                          storeAs: s
                          cases:
                            - when: { default: true }
                              do:
                                - setFlag: simple
                                - setFlag: { with_value: 42 }
                                - setFlags: [a, b, c]
                                - notifyParent: { type: BLOCKED, summary: "halted" }
                                - escalateTo: sub-strategy-name
                    """;
            ScorerSpec scorer = StrategyResolver.parseStrategy(yaml, "test:kitchen-sink")
                    .getPhases().get(0).getScorer();
            var actions = scorer.getCases().get(0).getDoActions();
            assertThat(actions).hasSize(5);
            assertThat(actions.get(0)).isInstanceOfSatisfying(BranchAction.SetFlag.class, sf -> {
                assertThat(sf.name()).isEqualTo("simple");
                assertThat(sf.value()).isEqualTo(Boolean.TRUE);
            });
            assertThat(actions.get(1)).isInstanceOfSatisfying(BranchAction.SetFlag.class, sf -> {
                assertThat(sf.name()).isEqualTo("with_value");
                assertThat(sf.value()).isEqualTo(42);
            });
            assertThat(actions.get(2)).isInstanceOfSatisfying(BranchAction.SetFlags.class, sf ->
                    assertThat(sf.names()).containsExactly("a", "b", "c"));
            assertThat(actions.get(3)).isInstanceOfSatisfying(BranchAction.NotifyParent.class, np -> {
                assertThat(np.type()).isEqualTo("BLOCKED");
                assertThat(np.summary()).isEqualTo("halted");
            });
            assertThat(actions.get(4)).isInstanceOfSatisfying(BranchAction.EscalateTo.class, et -> {
                assertThat(et.strategy()).isEqualTo("sub-strategy-name");
                assertThat(et.params()).isEmpty();
            });
        }

        @Test
        void parsesPauseAndExitStrategy() {
            String yaml = """
                    name: terminal
                    phases:
                      - name: p
                        worker: w
                        scorer:
                          storeAs: s
                          cases:
                            - when: { scoreBelow: 0.05 }
                              do:
                                - exitStrategy: fail
                            - when: { default: true }
                              do:
                                - pause: { reason: "needs human input" }
                    """;
            var phase = StrategyResolver.parseStrategy(yaml, "test:terminal")
                    .getPhases().get(0);
            assertThat(phase.getScorer().getCases().get(0).getDoActions().get(0))
                    .isInstanceOfSatisfying(BranchAction.ExitStrategy.class, es ->
                            assertThat(es.outcome()).isEqualTo(BranchAction.ExitOutcome.FAIL));
            assertThat(phase.getScorer().getCases().get(1).getDoActions().get(0))
                    .isInstanceOfSatisfying(BranchAction.Pause.class, p ->
                            assertThat(p.reason()).isEqualTo("needs human input"));
        }
    }
}
