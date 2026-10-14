package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import de.mhus.vance.api.question.QuestionAnswerDto;
import de.mhus.vance.api.question.QuestionOutcome;
import de.mhus.vance.api.question.QuestionSetAnswerDto;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Structured question-set answers have to survive the pending-queue round
 * trip: the engine reads the typed answers, never the readable short form in
 * {@code content}. A dropped field would degrade every answer to "not
 * answered" without any error.
 */
class SteerMessageCodecQuestionAnswersTest {

    @Test
    void encodeDecode_preservesSetIdAndPerQuestionOutcome() {
        QuestionSetAnswerDto answers = QuestionSetAnswerDto.builder()
                .setId("set-7")
                .answers(List.of(
                        QuestionAnswerDto.builder()
                                .key("retry-policy")
                                .outcome(QuestionOutcome.ANSWERED)
                                .value("3 attempts")
                                .build(),
                        QuestionAnswerDto.builder()
                                .key("rounding")
                                .outcome(QuestionOutcome.SETTLED)
                                .build()))
                .build();
        SteerMessage.UserChatInput original = new SteerMessage.UserChatInput(
                Instant.parse("2026-10-14T10:00:00Z"),
                null,
                "wile.coyote",
                "Wile",
                "retry-policy: 3 attempts · rounding: good as it is",
                List.of(),
                false,
                null,
                null,
                null,
                null,
                answers);

        PendingMessageDocument encoded = SteerMessageCodec.toDocument(original);
        SteerMessage.UserChatInput decoded = (SteerMessage.UserChatInput) SteerMessageCodec.toMessage(encoded);

        assertThat(decoded.questionAnswers()).isNotNull();
        assertThat(decoded.questionAnswers().getSetId()).isEqualTo("set-7");
        assertThat(decoded.questionAnswers().getAnswers())
                .extracting(QuestionAnswerDto::getKey, QuestionAnswerDto::getOutcome, QuestionAnswerDto::getValue)
                .containsExactly(
                        tuple("retry-policy", QuestionOutcome.ANSWERED, "3 attempts"),
                        tuple("rounding", QuestionOutcome.SETTLED, null));
    }

    @Test
    void encodeDecode_ordinaryTurn_keepsQuestionAnswersNull() {
        SteerMessage.UserChatInput original =
                new SteerMessage.UserChatInput(Instant.now(), null, "wile.coyote", "just chatting");

        SteerMessage.UserChatInput decoded =
                (SteerMessage.UserChatInput) SteerMessageCodec.toMessage(SteerMessageCodec.toDocument(original));

        assertThat(decoded.questionAnswers()).isNull();
    }
}
