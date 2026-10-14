package de.mhus.vance.foot.question;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.question.QuestionAnswerDto;
import de.mhus.vance.api.question.QuestionOutcome;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionsAnswersTest {

    @Test
    void setIdFor_matchesTheWebClientsHash_forASetWithoutId() {
        String body = "questions:\n  - key: v\n    text: Which variant? äö\n    kind: choice\n    options: [A, B]\n";

        // Reference value computed with setIdFor of questionsAnswers.ts.
        assertThat(QuestionsAnswers.setIdFor(QuestionsFence.parse(body), body)).isEqualTo("q-1yul5a2");
        assertThat(QuestionsAnswers.setIdFor(QuestionsFence.parse("id: s-1\n" + body), body))
                .isEqualTo("s-1");
    }

    @Test
    void readable_isJustTheAnswerForOneQuestion_linesForSeveral_openOnesLater() {
        var one = QuestionsFence.parse(
                "questions:\n  - key: v\n    text: Which?\n    kind: choice\n    options: [A, B]\n");
        var two = QuestionsFence.parse("questions:\n  - key: a\n    text: A?\n  - key: b\n    text: B?\n");
        var answered = Map.of(
                "v", QuestionAnswerDto.builder().key("v").value("B").build(),
                "a",
                        QuestionAnswerDto.builder()
                                .key("a")
                                .outcome(QuestionOutcome.SETTLED)
                                .build());

        assertThat(QuestionsAnswers.readable(one, answered, QuestionsAnswers.Labels.ENGLISH))
                .isEqualTo("B");
        assertThat(QuestionsAnswers.readable(two, answered, QuestionsAnswers.Labels.ENGLISH))
                .isEqualTo("- A? → good as it is\n- B? → later");
        assertThat(QuestionsAnswers.toSetAnswer("s", two, answered).getAnswers())
                .extracting(QuestionAnswerDto::getOutcome)
                .containsExactly(QuestionOutcome.SETTLED, QuestionOutcome.LATER);
    }
}
