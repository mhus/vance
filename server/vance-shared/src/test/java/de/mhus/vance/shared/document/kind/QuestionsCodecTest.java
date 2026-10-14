package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOptionDto;
import de.mhus.vance.api.question.QuestionSetDto;
import de.mhus.vance.api.question.QuestionWeight;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionsCodecTest {

    private static QuestionSetDto set() {
        return QuestionSetDto.builder()
                .id("set-1")
                .intro("Two points")
                .questions(new ArrayList<>(List.of(
                        QuestionDto.builder()
                                .key("region")
                                .text("Which region?")
                                .kind(QuestionKind.CHOICE)
                                .options(new ArrayList<>(List.of(
                                        QuestionOptionDto.builder()
                                                .label("eu-central-1")
                                                .build(),
                                        QuestionOptionDto.builder()
                                                .label("eu-west-1")
                                                .build())))
                                .context("must be stored within the EU")
                                .weight(QuestionWeight.BLOCKER)
                                .build(),
                        QuestionDto.builder()
                                .key("retention")
                                .text("10 years?")
                                .kind(QuestionKind.CONFIRM)
                                .proposal("10 years")
                                .build())))
                .build();
    }

    @Test
    void toFence_roundTripsThroughParse() {
        String fence = QuestionsCodec.toFence(set());

        assertThat(fence).startsWith("```questions\n").endsWith("```\n");
        String body = fence.substring("```questions\n".length(), fence.length() - "```\n".length());
        QuestionSetDto back = QuestionsCodec.parse(body, null);

        assertThat(back.getId()).isEqualTo("set-1");
        assertThat(back.getIntro()).isEqualTo("Two points");
        assertThat(back.getQuestions()).extracting(QuestionDto::getKey).containsExactly("region", "retention");
        assertThat(back.getQuestions().get(0).getWeight()).isEqualTo(QuestionWeight.BLOCKER);
        assertThat(back.getQuestions().get(0).getOptions())
                .extracting(QuestionOptionDto::getLabel)
                .containsExactly("eu-central-1", "eu-west-1");
        assertThat(back.getQuestions().get(1).getProposal()).isEqualTo("10 years");
    }

    @Test
    void serialize_yamlCarriesTheKindHeader() {
        assertThat(QuestionsCodec.serialize(set(), "application/yaml")).startsWith("$meta:\n  kind: questions\n");
    }
}
