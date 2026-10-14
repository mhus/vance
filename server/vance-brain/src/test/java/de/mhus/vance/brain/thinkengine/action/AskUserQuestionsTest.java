package de.mhus.vance.brain.thinkengine.action;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOptionDto;
import de.mhus.vance.api.question.QuestionSetDto;
import de.mhus.vance.shared.document.kind.QuestionsCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ASK_USER with options goes out as a {@code questions} fence that passes the
 * kind's own validator; without options it stays a plain question.
 */
class AskUserQuestionsTest {

    private static QuestionSetDto parseFence(String rendered) {
        assertThat(rendered).startsWith("```questions\n").endsWith("```\n");
        String body = rendered.substring("```questions\n".length(), rendered.length() - "```\n".length());
        return QuestionsCodec.parse(body, "application/yaml");
    }

    @Test
    void options_becomeAChoiceQuestion_thatTheValidatorAccepts() {
        String message = "Which **variant** shall I build?\n\nBoth are possible.";

        String rendered = AskUserQuestions.render(
                message,
                List.of(
                        Map.of("label", "A", "description", "fast"),
                        Map.of("label", " B "),
                        Map.of("description", "no label — dropped")));

        QuestionSetDto set = parseFence(rendered);
        assertThat(set.getId()).isEmpty();
        assertThat(set.getQuestions()).singleElement().satisfies(q -> {
            assertThat(q.getKind()).isEqualTo(QuestionKind.CHOICE);
            assertThat(q.getText()).isEqualTo(message);
            assertThat(q.getOptions())
                    .extracting(QuestionOptionDto::getLabel, QuestionOptionDto::getDescription)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("A", "fast"),
                            org.assertj.core.groups.Tuple.tuple("B", null));
        });
    }

    @Test
    void singleOption_isOfferedOnATextQuestion() {
        QuestionSetDto set = parseFence(AskUserQuestions.render("Go on?", List.of(Map.of("label", "Yes"))));

        assertThat(set.getQuestions().get(0).getKind()).isEqualTo(QuestionKind.TEXT);
        assertThat(set.getQuestions().get(0).getOptions())
                .extracting(QuestionOptionDto::getLabel)
                .containsExactly("Yes");
    }

    @Test
    void noUsableOptions_keepsThePlainQuestion() {
        assertThat(AskUserQuestions.render("What next?", null)).isEqualTo("What next?");
        assertThat(AskUserQuestions.render("What next?", List.of(Map.of("label", " "))))
                .isEqualTo("What next?");
        assertThat(AskUserQuestions.render("What next?", "not a list")).isEqualTo("What next?");
    }
}
