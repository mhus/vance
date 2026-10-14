package de.mhus.vance.foot.question;

import de.mhus.vance.api.question.QuestionAnswerDto;
import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionOutcome;
import de.mhus.vance.api.question.QuestionSetAnswerDto;
import de.mhus.vance.api.question.QuestionSetDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The answers of a question set as they leave the client — the CLI mirror of
 * {@code questionsAnswers.ts}: the typed {@code questionAnswers} plus the
 * readable text every engine understands. Questions left open go as "later".
 */
public final class QuestionsAnswers {

    /** Words for the standard exits. */
    public record Labels(String settled, String later, String unknown, String confirmed, String open) {

        public static final Labels ENGLISH = new Labels("good as it is", "later", "don't know", "confirmed", "—");
    }

    private QuestionsAnswers() {}

    /**
     * Stable id for a set without one — the same djb2 hash over the fence body
     * the web client uses, so both clients name the same set alike.
     */
    public static String setIdFor(QuestionSetDto set, String fenceBody) {
        if (set.getId() != null && !set.getId().isBlank()) {
            return set.getId();
        }
        int h = 5381;
        for (int i = 0; i < fenceBody.length(); i++) {
            h = (h << 5) + h + fenceBody.charAt(i);
        }
        return "q-" + Integer.toUnsignedString(h, 36);
    }

    public static String answerLabel(@Nullable QuestionAnswerDto answer, Labels labels) {
        if (answer == null) {
            return labels.open();
        }
        return switch (answer.getOutcome()) {
            case SETTLED -> labels.settled();
            case LATER -> labels.later();
            case UNKNOWN -> labels.unknown();
            // An answer without a value is an agreement — "confirmed".
            default ->
                answer.getValue() == null || answer.getValue().isBlank() ? labels.confirmed() : answer.getValue();
        };
    }

    /**
     * The message text. One question (an engine's ASK_USER): just the answer,
     * as if typed. Several: one line per question, "text → answer".
     */
    public static String readable(QuestionSetDto set, Map<String, QuestionAnswerDto> answers, Labels labels) {
        if (set.getQuestions().size() == 1) {
            QuestionDto only = set.getQuestions().get(0);
            return answerLabel(answerOrLater(answers, only), labels);
        }
        return set.getQuestions().stream()
                .map(q -> "- " + q.getText() + " → " + answerLabel(answerOrLater(answers, q), labels))
                .collect(Collectors.joining("\n"));
    }

    public static QuestionSetAnswerDto toSetAnswer(
            String setId, QuestionSetDto set, Map<String, QuestionAnswerDto> answers) {
        List<QuestionAnswerDto> out = new ArrayList<>();
        for (QuestionDto q : set.getQuestions()) {
            out.add(answerOrLater(answers, q));
        }
        return QuestionSetAnswerDto.builder().setId(setId).answers(out).build();
    }

    private static QuestionAnswerDto answerOrLater(Map<String, QuestionAnswerDto> answers, QuestionDto q) {
        QuestionAnswerDto a = answers.get(q.getKey());
        return a != null
                ? a
                : QuestionAnswerDto.builder()
                        .key(q.getKey())
                        .outcome(QuestionOutcome.LATER)
                        .build();
    }
}
