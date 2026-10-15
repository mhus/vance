package de.mhus.vance.brain.wonko;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionSetDto;
import de.mhus.vance.shared.util.JsonSchemaLight;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A free message after (or between) the rounds is not always a reason to
 * review again — "what do you think of it?" wants an answer, "I changed the
 * document" a fresh run.
 */
class WonkoRouteTest {

    @Test
    void schema_requiresIntentAndReason() {
        assertThat(JsonSchemaLight.validate(
                                Map.of("intent", "question", "reason", "asks", "answer", "Fine."),
                                WonkoPrompts.routeSchema(false))
                        .valid())
                .isTrue();
        assertThat(JsonSchemaLight.validate(Map.of("intent", "question"), WonkoPrompts.routeSchema(false))
                        .valid())
                .isFalse();
        assertThat(JsonSchemaLight.validate(Map.of("intent", "chat", "reason", "x"), WonkoPrompts.routeSchema(false))
                        .valid())
                .isFalse();
    }

    @Test
    void schema_offersWorkerOnlyWhileAWorkerWaits() {
        Map<String, Object> worker = Map.of("intent", "worker", "reason", "answers the worker");
        assertThat(JsonSchemaLight.validate(worker, WonkoPrompts.routeSchema(true))
                        .valid())
                .isTrue();
        assertThat(JsonSchemaLight.validate(worker, WonkoPrompts.routeSchema(false))
                        .valid())
                .isFalse();
        assertThat(WonkoPrompts.parseRoute(worker).intent()).isEqualTo(WonkoPrompts.RouteIntent.WORKER);
    }

    @Test
    void routePrompt_namesTheWaitingWorkerAndTheClosedOffer() {
        String prompt = WonkoPrompts.routePrompt(
                List.of("req.md"), List.of(), null, null, List.of(), "A", "Which one: A or B?", "work the results in");

        assertThat(prompt)
                .contains("## A worker waits for the user", "Which one: A or B?", "Intent worker")
                .contains("## Your offer the user did not confirm", "You offered to run: work the results in")
                .endsWith("## The user's message\nA\n");
    }

    @Test
    void parseRoute_readsIntentAndAnswer() {
        var question = WonkoPrompts.parseRoute(
                Map.of("intent", "question", "reason", "asks for an opinion", "answer", "Two gaps are open."));
        assertThat(question.intent()).isEqualTo(WonkoPrompts.RouteIntent.QUESTION);
        assertThat(question.answer()).isEqualTo("Two gaps are open.");

        var recheck = WonkoPrompts.parseRoute(Map.of("intent", "recheck", "reason", "document changed"));
        assertThat(recheck.intent()).isEqualTo(WonkoPrompts.RouteIntent.RECHECK);
        assertThat(recheck.answer()).isNull();
    }

    @Test
    void parseRoute_questionWithoutAnswer_isUnclear_unknownIntentToo() {
        assertThat(WonkoPrompts.parseRoute(Map.of("intent", "question", "reason", "x"))
                        .intent())
                .isEqualTo(WonkoPrompts.RouteIntent.UNCLEAR);
        assertThat(WonkoPrompts.parseRoute(Map.of("intent", "smalltalk", "reason", "x", "answer", "Hi"))
                        .intent())
                .isEqualTo(WonkoPrompts.RouteIntent.UNCLEAR);
    }

    @Test
    void routePrompt_carriesSubjectReportOpenQuestionsAndMessage() {
        QuestionSetDto pending = QuestionSetDto.builder()
                .id("s1")
                .questions(List.of(QuestionDto.builder()
                        .key("region")
                        .text("Which region?")
                        .build()))
                .build();

        String prompt = WonkoPrompts.routePrompt(
                List.of("requirements/export.md"),
                List.of(new WonkoPrompts.SubjectDoc("requirements/export.md", "Exports go to us-west-2.")),
                "# Review\n- Region open",
                pending,
                List.of(
                        new WonkoPrompts.Turn("assistant", "Shall I work the five points in?"),
                        new WonkoPrompts.Turn("user", "x".repeat(700))),
                "what do you think?",
                null,
                null);

        assertThat(prompt)
                .contains(
                        "requirements/export.md",
                        "Exports go to us-west-2.",
                        "# Review",
                        "Which region?",
                        "- assistant: Shall I work the five points in?",
                        "what do you think?")
                .contains("x".repeat(600) + " …")
                .doesNotContain("x".repeat(601));
    }

    @Test
    void resetRun_dropsRunState_keepsSubjectAndStatements() {
        WonkoState state = new WonkoState();
        state.setSubjectRefs(List.of("requirements/export.md"));
        state.setSubjectKey("documents:requirements/export.md");
        state.getUserStatements().add("bucket is in the EU now");
        state.setRound(3);
        state.getClaims().add(new WonkoClaim());
        state.getPostponedKeys().add("retention");
        state.setFinished(true);
        state.setFinalReport("# Review");

        state.resetRun();

        assertThat(state.getSubjectRefs()).containsExactly("requirements/export.md");
        assertThat(state.getSubjectKey()).isEqualTo("documents:requirements/export.md");
        assertThat(state.getUserStatements()).containsExactly("bucket is in the EU now");
        assertThat(state.getRound()).isZero();
        assertThat(state.getClaims()).isEmpty();
        assertThat(state.getPostponedKeys()).isEmpty();
        assertThat(state.isFinished()).isFalse();
        assertThat(state.getFinalReport()).isNull();
    }

    @Test
    void parseRoute_readsWorkTaskAndFollowUp() {
        var work = WonkoPrompts.parseRoute(Map.of(
                "intent", "work",
                "reason", "asks to change the document",
                "answer", "I will work the points in.",
                "task", "Work the clarified points into requirements/export.md"));
        org.assertj.core.api.Assertions.assertThat(work.intent()).isEqualTo(WonkoPrompts.RouteIntent.WORK);
        org.assertj.core.api.Assertions.assertThat(work.task()).contains("requirements/export.md");

        var recheck = WonkoPrompts.parseRoute(
                Map.of("intent", "recheck", "reason", "check requested", "followUp", "work the results in"));
        org.assertj.core.api.Assertions.assertThat(recheck.followUp()).isEqualTo("work the results in");
    }

    @Test
    void followUpQuestion_isAConfirm_namingOpenBlockers() {
        var q = WonkoPrompts.followUpQuestion("followup", "work the results in", List.of("Region", "Retention"));

        org.assertj.core.api.Assertions.assertThat(q.getKind())
                .isEqualTo(de.mhus.vance.api.question.QuestionKind.CONFIRM);
        org.assertj.core.api.Assertions.assertThat(q.getProposal()).isEqualTo("work the results in");
        org.assertj.core.api.Assertions.assertThat(q.getContext()).contains("Region; Retention");
        org.assertj.core.api.Assertions.assertThat(WonkoPrompts.followUpQuestion("followup", "x", List.of())
                        .getContext())
                .isNull();
    }

    @Test
    void confirmedFollowUp_entersTheIntake_otherAnswersDropIt() {
        var set = QuestionSetDto.builder()
                .id("s")
                .questions(List.of(WonkoPrompts.followUpQuestion(WonkoEngine.FOLLOWUP_KEY, "work it in", List.of())))
                .build();
        WonkoState confirmed = new WonkoState();
        confirmed.getFollowUps().add("work it in");
        WonkoEngine.takeFollowUpAnswer(
                confirmed,
                set,
                List.of(de.mhus.vance.api.question.QuestionAnswerDto.builder()
                        .key(WonkoEngine.FOLLOWUP_KEY)
                        .value("work it in")
                        .build()));
        org.assertj.core.api.Assertions.assertThat(confirmed.getFollowUps()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(confirmed.getFollowUpInputs())
                .containsExactly("work it in");

        WonkoState later = new WonkoState();
        later.getFollowUps().add("work it in");
        WonkoEngine.takeFollowUpAnswer(
                later,
                set,
                List.of(de.mhus.vance.api.question.QuestionAnswerDto.builder()
                        .key(WonkoEngine.FOLLOWUP_KEY)
                        .outcome(de.mhus.vance.api.question.QuestionOutcome.LATER)
                        .build()));
        org.assertj.core.api.Assertions.assertThat(later.getFollowUps()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(later.getFollowUpInputs()).isEmpty();
    }

    @Test
    void workTask_carriesTaskSubjectReviewAndRules() {
        String task =
                WonkoPrompts.workTask("Work the clarified points in.", List.of("req.md"), "# Review\n- Region: EU");

        org.assertj.core.api.Assertions.assertThat(task)
                .startsWith("Work the clarified points in.")
                .contains("- req.md", "# Review", "Region: EU", "leave points that are still open");
    }
}
