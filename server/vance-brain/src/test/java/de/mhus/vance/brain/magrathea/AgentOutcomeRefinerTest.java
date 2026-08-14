package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaRetrySpec;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AgentOutcomeRefinerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private static MagratheaStateSpec state(Map<String, Object> spec) {
        return new MagratheaStateSpec(
                "judge", MagratheaTaskType.AGENT_TASK, null, null, null,
                null, List.of(), Map.of(), Map.of(), List.of(),
                MagratheaRetrySpec.none(), spec);
    }

    // ──────────── spec reading ────────────

    @Test
    void stateWithoutJudgement_yieldsNone() {
        assertThat(AgentOutcomeRefiner.judgementOf(state(Map.of("recipe", "ford")))).isEmpty();
    }

    @Test
    void declaringBothDecideAndScore_isRejected() {
        assertThatThrownBy(() -> AgentOutcomeRefiner.judgementOf(state(Map.of(
                "decide", Map.of("options", List.of("a", "b")),
                "score", Map.of("bands", List.of(Map.of("default", true, "outcome", "x")))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one judgement");
    }

    @Test
    void decideWithoutOptions_defaultsToYesNo() {
        AgentOutcomeRefiner.Judgement j =
                AgentOutcomeRefiner.judgementOf(state(Map.of("decide", Map.of()))).orElseThrow();

        assertThat(((AgentOutcomeRefiner.Decide) j).options()).containsExactly("yes", "no");
    }

    @Test
    void scoreWithoutBands_isRejected() {
        assertThatThrownBy(() -> AgentOutcomeRefiner.judgementOf(
                state(Map.of("score", Map.of("maxCorrections", 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bands");
    }

    @Test
    void bandWithoutThresholdOrDefault_isRejected() {
        assertThatThrownBy(() -> AgentOutcomeRefiner.judgementOf(state(Map.of(
                "score", Map.of("bands", List.of(Map.of("outcome", "ok")))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("atLeast");
    }

    @Test
    void bandsThatDoNotDescend_areRejected() {
        // Read top-down, first match wins: ascending thresholds would
        // route 0.9 to 'revise' and never reach 'approved'.
        assertThatThrownBy(() -> AgentOutcomeRefiner.judgementOf(state(Map.of(
                "score", Map.of("bands", List.of(
                        Map.of("atLeast", 0.2, "outcome", "revise"),
                        Map.of("atLeast", 0.7, "outcome", "approved")))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("descend");
    }

    @Test
    void aDefaultBandThatIsNotLast_isRejected() {
        // Everything after it is unreachable — every score would come out
        // 'rejected'.
        assertThatThrownBy(() -> AgentOutcomeRefiner.judgementOf(state(Map.of(
                "score", Map.of("bands", List.of(
                        Map.of("default", true, "outcome", "rejected"),
                        Map.of("atLeast", 0.7, "outcome", "approved")))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be last");
    }

    @Test
    void aThresholdOutsideTheFixedScale_isRejected() {
        // The scale is 0.0–1.0 and answers outside it are re-asked, so a
        // band above it can never match.
        assertThatThrownBy(() -> AgentOutcomeRefiner.judgementOf(state(Map.of(
                "score", Map.of("bands", List.of(
                        Map.of("atLeast", 70, "outcome", "approved")))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0.0–1.0");
    }

    @Test
    void descendingBandsWithADefaultLast_areAccepted() {
        assertThat(AgentOutcomeRefiner.judgementOf(state(Map.of(
                "score", Map.of("bands", List.of(
                        Map.of("atLeast", 0.7, "outcome", "approved"),
                        Map.of("atLeast", 0.2, "outcome", "revise"),
                        Map.of("default", true, "outcome", "rejected"))))))).isPresent();
    }

    // ──────────── decide ────────────

    private AgentOutcomeRefiner.Judgement decide(String... options) {
        return AgentOutcomeRefiner.judgementOf(state(Map.of(
                "decide", Map.of("options", List.of(options))))).orElseThrow();
    }

    @Test
    void decide_tokenBecomesTheOutcome() {
        AgentOutcomeRefiner.Result r = AgentOutcomeRefiner.refine(
                decide("unambiguous", "ambiguous"), "ambiguous", objectMapper);

        assertThat(r).isInstanceOfSatisfying(AgentOutcomeRefiner.Decided.class,
                d -> assertThat(d.outcome()).isEqualTo("ambiguous"));
    }

    @Test
    void decide_isCaseInsensitiveAndIgnoresSurroundingProse() {
        AgentOutcomeRefiner.Result r = AgentOutcomeRefiner.refine(
                decide("retry", "abort"), "I would say: Retry, because the test is flaky.",
                objectMapper);

        assertThat(((AgentOutcomeRefiner.Decided) r).outcome()).isEqualTo("retry");
    }

    @Test
    void decide_matchesWholeWordsOnly() {
        // "noise" must not read as "no".
        AgentOutcomeRefiner.Result r = AgentOutcomeRefiner.refine(
                decide("yes", "no"), "there is too much noise in this data", objectMapper);

        assertThat(r).isInstanceOf(AgentOutcomeRefiner.NeedsCorrection.class);
    }

    @Test
    void decide_doesNotMatchInsideACompoundToken() {
        // Outcome tokens are written with '_' and '-', so those are part
        // of the word: "needs_work" is one word, and reading "work" out of
        // it would route on an option the model never chose.
        AgentOutcomeRefiner.Result r = AgentOutcomeRefiner.refine(
                decide("work", "wait"), "the answer is needs_work", objectMapper);

        assertThat(r).isInstanceOf(AgentOutcomeRefiner.NeedsCorrection.class);
    }

    @Test
    void decide_stillMatchesAHyphenatedOptionItself() {
        AgentOutcomeRefiner.Result r = AgentOutcomeRefiner.refine(
                decide("go", "no-go"), "I say no-go on this one", objectMapper);

        assertThat(((AgentOutcomeRefiner.Decided) r).outcome()).isEqualTo("no-go");
    }

    @Test
    void decide_unknownAnswer_asksAgainWithTheOptions() {
        AgentOutcomeRefiner.Result r = AgentOutcomeRefiner.refine(
                decide("retry", "abort"), "not sure honestly", objectMapper);

        assertThat(r).isInstanceOfSatisfying(AgentOutcomeRefiner.NeedsCorrection.class,
                c -> assertThat(c.hint()).contains("retry", "abort"));
    }

    @Test
    void decide_emptyAnswer_asksAgain() {
        assertThat(AgentOutcomeRefiner.refine(decide("a", "b"), "  ", objectMapper))
                .isInstanceOf(AgentOutcomeRefiner.NeedsCorrection.class);
    }

    // ──────────── score ────────────

    private AgentOutcomeRefiner.Judgement scoring() {
        return AgentOutcomeRefiner.judgementOf(state(Map.of("score", Map.of(
                "bands", List.of(
                        Map.of("atLeast", 0.7, "outcome", "approved"),
                        Map.of("atLeast", 0.2, "outcome", "revise"),
                        Map.of("default", true, "outcome", "rejected")))))).orElseThrow();
    }

    @Test
    void score_mapsToTheFirstMatchingBand() {
        AgentOutcomeRefiner.Result r = AgentOutcomeRefiner.refine(
                scoring(), "Here you go:\n{\"score\": 0.82, \"summary\": \"solid\"}",
                objectMapper);

        assertThat(r).isInstanceOfSatisfying(AgentOutcomeRefiner.Decided.class, d -> {
            assertThat(d.outcome()).isEqualTo("approved");
            // The whole object is kept, so storeAs captures the detail.
            assertThat(d.output().get("summary").asString()).isEqualTo("solid");
        });
    }

    @Test
    void score_belowEveryThreshold_takesTheDefaultBand() {
        AgentOutcomeRefiner.Result r = AgentOutcomeRefiner.refine(
                scoring(), "{\"score\": 0.05}", objectMapper);

        assertThat(((AgentOutcomeRefiner.Decided) r).outcome()).isEqualTo("rejected");
    }

    @Test
    void score_onTheThreshold_countsAsMeetingIt() {
        AgentOutcomeRefiner.Result r = AgentOutcomeRefiner.refine(
                scoring(), "{\"score\": 0.7}", objectMapper);

        assertThat(((AgentOutcomeRefiner.Decided) r).outcome()).isEqualTo("approved");
    }

    @Test
    void score_outsideTheFixedScale_asksAgain() {
        // A 0–10 answer would map to "approved" if taken at face value,
        // which is the failure mode the fixed scale exists to prevent.
        AgentOutcomeRefiner.Result r = AgentOutcomeRefiner.refine(
                scoring(), "{\"score\": 8}", objectMapper);

        assertThat(r).isInstanceOfSatisfying(AgentOutcomeRefiner.NeedsCorrection.class,
                c -> assertThat(c.hint()).contains("0.0", "1.0"));
    }

    @Test
    void score_withoutJson_asksAgain() {
        assertThat(AgentOutcomeRefiner.refine(scoring(), "it was pretty good", objectMapper))
                .isInstanceOf(AgentOutcomeRefiner.NeedsCorrection.class);
    }

    @Test
    void score_withoutAScoreField_asksAgain() {
        assertThat(AgentOutcomeRefiner.refine(scoring(), "{\"summary\": \"ok\"}", objectMapper))
                .isInstanceOf(AgentOutcomeRefiner.NeedsCorrection.class);
    }

    @Test
    void score_withNoMatchingBandAndNoDefault_failsTheStateInsteadOfReAsking() {
        // The model answered correctly; the plan just never said what this
        // score means. Asking again cannot fix an authoring gap.
        AgentOutcomeRefiner.Judgement narrow = AgentOutcomeRefiner.judgementOf(state(Map.of(
                "score", Map.of("bands", List.of(
                        Map.of("atLeast", 0.9, "outcome", "approved")))))).orElseThrow();

        AgentOutcomeRefiner.Result r =
                AgentOutcomeRefiner.refine(narrow, "{\"score\": 0.1}", objectMapper);

        assertThat(((AgentOutcomeRefiner.Decided) r).outcome()).isEqualTo("agent_error");
    }

    @Test
    void maxCorrections_defaultsWhenUnspecified() {
        assertThat(decide("a", "b").maxCorrections())
                .isEqualTo(AgentOutcomeRefiner.DEFAULT_MAX_CORRECTIONS);
    }

    @Test
    void maxCorrections_isReadFromTheSpec() {
        Optional<AgentOutcomeRefiner.Judgement> j = AgentOutcomeRefiner.judgementOf(state(Map.of(
                "decide", Map.of("options", List.of("a", "b"), "maxCorrections", 5))));

        assertThat(j.orElseThrow().maxCorrections()).isEqualTo(5);
    }
}
