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
                                WonkoPrompts.routeSchema())
                        .valid())
                .isTrue();
        assertThat(JsonSchemaLight.validate(Map.of("intent", "question"), WonkoPrompts.routeSchema())
                        .valid())
                .isFalse();
        assertThat(JsonSchemaLight.validate(Map.of("intent", "chat", "reason", "x"), WonkoPrompts.routeSchema())
                        .valid())
                .isFalse();
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
                List.of("requirements/export.md"), "# Review\n- Region open", pending, "what do you think?");

        assertThat(prompt).contains("requirements/export.md", "# Review", "Which region?", "what do you think?");
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
}
