package de.mhus.vance.brain.wonko;

import static de.mhus.vance.brain.wonko.WonkoTestData.contradiction;
import static de.mhus.vance.brain.wonko.WonkoTestData.dependent;
import static de.mhus.vance.brain.wonko.WonkoTestData.finding;
import static de.mhus.vance.brain.wonko.WonkoTestData.register;
import static de.mhus.vance.brain.wonko.WonkoTestData.topic;
import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionWeight;
import java.util.List;
import org.junit.jupiter.api.Test;

class WonkoQuestionPlannerTest {

    @Test
    void settledTopic_closesItsWholeBranch() {
        WonkoRegister r = register(MandateLevel.ASK);
        topic(r, "retry-policy", WonkoTopicStatus.SETTLED);

        var plan = WonkoQuestionPlanner.plan(
                r,
                List.of(
                        finding("retry-policy/backoff", QuestionWeight.IMPORTANT, null, null),
                        finding("rounding", QuestionWeight.IMPORTANT, null, null)),
                2,
                5);

        assertThat(plan.questions()).extracting(QuestionDto::getKey).containsExactly("rounding");
        assertThat(plan.suppressed()).isEqualTo(1);
    }

    @Test
    void answeredTopic_comesBackOnlyAsContradiction_andOnlyOnce() {
        WonkoRegister r = register(MandateLevel.ASK);
        topic(r, "a", WonkoTopicStatus.ANSWERED);
        topic(r, "b", WonkoTopicStatus.ANSWERED);

        var first = WonkoQuestionPlanner.plan(
                r, List.of(finding("a", QuestionWeight.IMPORTANT, null, null), contradiction("b")), 2, 5);
        assertThat(first.questions()).extracting(QuestionDto::getKey).containsExactly("b");

        r.topic("b").orElseThrow().setStatus(WonkoTopicStatus.ANSWERED);
        var second = WonkoQuestionPlanner.plan(r, List.of(contradiction("b")), 3, 5);

        assertThat(second.questions()).isEmpty();
        assertThat(r.topic("b").orElseThrow().getStatus()).isEqualTo(WonkoTopicStatus.CONFLICT);
    }

    @Test
    void decideCategoryWithProposal_isDecidedNotAsked() {
        WonkoRegister r = register(MandateLevel.ASK);
        r.getMandate()
                .getCategories()
                .put(
                        "naming",
                        WonkoCategory.builder().level(MandateLevel.DECIDE).build());

        var plan = WonkoQuestionPlanner.plan(
                r, List.of(finding("field-name", QuestionWeight.IMPORTANT, "naming", "amountMinor")), 1, 5);

        assertThat(plan.questions()).isEmpty();
        assertThat(plan.decided()).singleElement().satisfies(t -> {
            assertThat(t.getStatus()).isEqualTo(WonkoTopicStatus.DECIDED);
            assertThat(t.getDecision()).isEqualTo("amountMinor");
        });
    }

    @Test
    void blocker_isAtLeastConfirmed_evenUnderDecideMandate() {
        WonkoRegister r = register(MandateLevel.DECIDE);

        var plan = WonkoQuestionPlanner.plan(
                r, List.of(finding("currency", QuestionWeight.BLOCKER, null, "EUR only")), 1, 5);

        assertThat(plan.decided()).isEmpty();
        assertThat(plan.questions()).singleElement().satisfies(q -> {
            assertThat(q.getKind()).isEqualTo(QuestionKind.CONFIRM);
            assertThat(q.getProposal()).isEqualTo("EUR only");
        });
    }

    @Test
    void askMandate_turnsAProposalIntoAnOpenQuestion() {
        WonkoRegister r = register(MandateLevel.ASK);

        var plan =
                WonkoQuestionPlanner.plan(r, List.of(finding("timeout", QuestionWeight.IMPORTANT, null, "30 s")), 1, 5);

        assertThat(plan.questions())
                .singleElement()
                .extracting(QuestionDto::getKind)
                .isEqualTo(QuestionKind.TEXT);
    }

    @Test
    void detailWaitsForItsOpenRoot() {
        WonkoRegister r = register(MandateLevel.ASK);

        var plan = WonkoQuestionPlanner.plan(
                r,
                List.of(
                        finding("retries", QuestionWeight.IMPORTANT, null, null),
                        dependent("retries/count", "retries")),
                1,
                5);

        assertThat(plan.questions()).extracting(QuestionDto::getKey).containsExactly("retries");
        assertThat(plan.deferred()).isEqualTo(1);
    }

    @Test
    void blockersFirst_minorNeverAsked_budgetCaps() {
        WonkoRegister r = register(MandateLevel.ASK);

        var plan = WonkoQuestionPlanner.plan(
                r,
                List.of(
                        finding("a", QuestionWeight.IMPORTANT, null, null),
                        finding("b", QuestionWeight.MINOR, null, null),
                        finding("c", QuestionWeight.BLOCKER, null, null),
                        finding("d", QuestionWeight.IMPORTANT, null, null)),
                1,
                2);

        assertThat(plan.questions()).extracting(QuestionDto::getKey).containsExactly("c", "a");
        assertThat(r.topic("b").orElseThrow().getStatus()).isEqualTo(WonkoTopicStatus.OPEN);
        assertThat(r.topic("d").orElseThrow().getStatus()).isEqualTo(WonkoTopicStatus.OPEN);
        assertThat(r.topic("c").orElseThrow().getStatus()).isEqualTo(WonkoTopicStatus.ASKED);
    }

    @Test
    void laterInThisRun_isNotAskedAgain_butInTheNextRun() {
        WonkoRegister r = register(MandateLevel.ASK);
        topic(r, "deletion", WonkoTopicStatus.LATER);

        var sameRun = WonkoQuestionPlanner.plan(
                r,
                List.of(finding("deletion", QuestionWeight.BLOCKER, null, null)),
                3,
                5,
                java.util.Set.of("deletion"));
        assertThat(sameRun.questions()).isEmpty();
        assertThat(r.topic("deletion").orElseThrow().getStatus()).isEqualTo(WonkoTopicStatus.LATER);

        var nextRun =
                WonkoQuestionPlanner.plan(r, List.of(finding("deletion", QuestionWeight.BLOCKER, null, null)), 1, 5);
        assertThat(nextRun.questions()).extracting(QuestionDto::getKey).containsExactly("deletion");
    }
}
