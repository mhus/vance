package de.mhus.vance.brain.vogon;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.vogon.BranchAction;
import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.ScoreMatch;
import de.mhus.vance.api.vogon.ScorerCase;
import de.mhus.vance.api.vogon.ScorerSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import de.mhus.vance.api.vogon.StrategyState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure unit tests for {@link ScorerEvaluator}. Drives JSON-extraction,
 * schema-validation and switch-evaluation without an LLM — replies
 * are pre-canned strings that mimic what a worker would emit.
 */
class ScorerEvaluatorTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void parsesScoreAndPersistsFlagsPlusArtifact() {
        ScorerSpec spec = lectorSpec();
        StrategyState state = new StrategyState();
        StrategySpec strategy = strategy(workerPhase("lector"));

        String reply = """
                Looks decent. Final verdict:
                {"score": 0.82, "summary": "ok with quibbles", "issues": []}
                """;

        ScorerEvaluator.Result r = ScorerEvaluator.evaluate(
                strategy, state, workerPhase("lector"), "lector",
                spec, reply, mapper);

        assertThat(r.outcome()).isEqualTo(ScorerEvaluator.Outcome.COMPLETED);
        assertThat(state.getFlags())
                .containsEntry("lector_score", 0.82)
                .containsEntry("lector_summary", "ok with quibbles");
        // Full object stored under storeAs key.
        assertThat(state.getFlags()).containsKey("lector");
        // ScorerOutput stashed into phaseArtifacts under the supplied key.
        assertThat(state.getPhaseArtifacts()).containsKey("lector");
        @SuppressWarnings("unchecked")
        Map<String, Object> art = state.getPhaseArtifacts().get("lector");
        assertThat(art).containsKey("scorerOutput");
    }

    @Test
    void scoreAtLeastTriggersExitLoop() {
        ScorerSpec spec = lectorSpec();
        StrategyState state = new StrategyState();
        StrategySpec strategy = strategy(workerPhase("lector"));

        String reply = "{\"score\": 0.8, \"summary\": \"fine\"}";
        ScorerEvaluator.Result r = ScorerEvaluator.evaluate(
                strategy, state, workerPhase("lector"), "lector",
                spec, reply, mapper);

        assertThat(r.branchResult().kind())
                .isEqualTo(BranchActionExecutor.ResultKind.EXIT_LOOP);
        assertThat(state.getFlags()).containsEntry("lector_approved", Boolean.TRUE);
    }

    @Test
    void scoreBelowTriggersEscalateAndSetsHardRejectFlag() {
        ScorerSpec spec = lectorSpec();
        StrategyState state = new StrategyState();
        StrategySpec strategy = strategy(workerPhase("lector"));

        String reply = "{\"score\": 0.05, \"summary\": \"unusable\"}";
        ScorerEvaluator.Result r = ScorerEvaluator.evaluate(
                strategy, state, workerPhase("lector"), "lector",
                spec, reply, mapper);

        assertThat(r.branchResult().kind())
                .isEqualTo(BranchActionExecutor.ResultKind.ESCALATED);
        assertThat(r.branchResult().detail()).isEqualTo("deeper-review");
        assertThat(state.getFlags()).containsEntry("lector_rejected_hard", Boolean.TRUE);
    }

    @Test
    void midRangeScoreTriggersDefaultCase() {
        ScorerSpec spec = lectorSpec();
        StrategyState state = new StrategyState();
        StrategySpec strategy = strategy(workerPhase("lector"));

        String reply = "{\"score\": 0.5, \"summary\": \"meh\"}";
        ScorerEvaluator.Result r = ScorerEvaluator.evaluate(
                strategy, state, workerPhase("lector"), "lector",
                spec, reply, mapper);

        assertThat(r.branchResult().kind())
                .isEqualTo(BranchActionExecutor.ResultKind.CONTINUE);
        assertThat(state.getFlags()).containsEntry("lector_revision_needed", Boolean.TRUE);
        assertThat(state.getFlags())
                .doesNotContainKey("lector_approved")
                .doesNotContainKey("lector_rejected_hard");
    }

    @Test
    void emptyReplyReturnsNeedsCorrection() {
        ScorerEvaluator.Result r = ScorerEvaluator.evaluate(
                strategy(), new StrategyState(), workerPhase("lector"), "lector",
                lectorSpec(), "", mapper);
        assertThat(r.outcome()).isEqualTo(ScorerEvaluator.Outcome.NEEDS_CORRECTION);
        assertThat(r.correctionHint()).contains("empty");
    }

    @Test
    void replyWithoutJsonReturnsNeedsCorrection() {
        ScorerEvaluator.Result r = ScorerEvaluator.evaluate(
                strategy(), new StrategyState(), workerPhase("lector"), "lector",
                lectorSpec(), "I think it's pretty good but no JSON here.", mapper);
        assertThat(r.outcome()).isEqualTo(ScorerEvaluator.Outcome.NEEDS_CORRECTION);
        assertThat(r.correctionHint()).contains("No JSON object");
    }

    @Test
    void missingScoreFieldReturnsNeedsCorrection() {
        ScorerEvaluator.Result r = ScorerEvaluator.evaluate(
                strategy(), new StrategyState(), workerPhase("lector"), "lector",
                lectorSpec(), "{\"summary\": \"oops\"}", mapper);
        assertThat(r.outcome()).isEqualTo(ScorerEvaluator.Outcome.NEEDS_CORRECTION);
        assertThat(r.correctionHint()).contains("'score'");
    }

    @Test
    void scoreOutOfRangeReturnsNeedsCorrection() {
        ScorerEvaluator.Result r = ScorerEvaluator.evaluate(
                strategy(), new StrategyState(), workerPhase("lector"), "lector",
                lectorSpec(), "{\"score\": 1.5, \"summary\": \"x\"}", mapper);
        assertThat(r.outcome()).isEqualTo(ScorerEvaluator.Outcome.NEEDS_CORRECTION);
        assertThat(r.correctionHint()).contains("[0.0, 1.0]");
    }

    @Test
    void schemaDeclaredFieldMissingReturnsNeedsCorrection() {
        ScorerSpec spec = ScorerSpec.builder()
                .storeAs("s")
                .schema(java.util.Map.of("score", "float", "verdict", "string"))
                .cases(new ArrayList<>(List.of(
                        ScorerCase.builder()
                                .when(ScoreMatch.builder().defaultMatch(true).build())
                                .doActions(List.of(new BranchAction.SetFlag("done")))
                                .build())))
                .build();

        ScorerEvaluator.Result r = ScorerEvaluator.evaluate(
                strategy(), new StrategyState(), workerPhase("p"), "p",
                spec, "{\"score\": 0.7}", mapper);

        assertThat(r.outcome()).isEqualTo(ScorerEvaluator.Outcome.NEEDS_CORRECTION);
        assertThat(r.correctionHint()).contains("'verdict'");
    }

    @Test
    void worksWithReplyContainingExampleObjectsBeforeFinal() {
        // The extractor picks the LAST top-level JSON object — so an
        // earlier example or quoted object in the reply is tolerated.
        String reply = """
                Example would have been: {"score": 0.0, "summary": "bad"}
                Actual:
                {"score": 0.9, "summary": "great"}
                """;
        StrategyState state = new StrategyState();
        ScorerEvaluator.Result r = ScorerEvaluator.evaluate(
                strategy(), state, workerPhase("lector"), "lector",
                lectorSpec(), reply, mapper);

        assertThat(r.branchResult().kind())
                .isEqualTo(BranchActionExecutor.ResultKind.EXIT_LOOP);
        assertThat(state.getFlags()).containsEntry("lector_score", 0.9);
    }

    // ──────────────── helpers ────────────────

    /** Three-case lector spec from the spec example: < 0.2 escalate,
     *  >= 0.7 setFlag+exitLoop, default revision_needed. */
    private static ScorerSpec lectorSpec() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("score", "float");
        schema.put("summary", "string");
        return ScorerSpec.builder()
                .storeAs("lector")
                .schema(schema)
                .cases(new ArrayList<>(List.of(
                        ScorerCase.builder()
                                .when(ScoreMatch.builder().scoreBelow(0.2).build())
                                .doActions(List.of(
                                        new BranchAction.SetFlag("lector_rejected_hard"),
                                        new BranchAction.EscalateTo("deeper-review")))
                                .build(),
                        ScorerCase.builder()
                                .when(ScoreMatch.builder().scoreAtLeast(0.7).build())
                                .doActions(List.of(
                                        new BranchAction.SetFlag("lector_approved"),
                                        new BranchAction.ExitLoop(
                                                BranchAction.ExitOutcome.OK)))
                                .build(),
                        ScorerCase.builder()
                                .when(ScoreMatch.builder().defaultMatch(true).build())
                                .doActions(List.of(
                                        new BranchAction.SetFlag("lector_revision_needed")))
                                .build())))
                .build();
    }

    private static PhaseSpec workerPhase(String name) {
        return PhaseSpec.builder().name(name).worker("dummy").build();
    }

    private static StrategySpec strategy(PhaseSpec... phases) {
        return StrategySpec.builder()
                .name("t")
                .phases(new ArrayList<>(java.util.Arrays.asList(phases)))
                .build();
    }
}
