package de.mhus.vance.brain.wonko;

import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionWeight;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One finding of a check round, as the model reported it — already
 * normalised (keys, enums). Becomes or updates a register topic.
 *
 * @param contradictsUser the finding contradicts something the user stated
 *     explicitly — raises the mandate floor to at least confirm (§6.2)
 * @param reason why this is a finding — mandatory in every model output (§4a.8)
 * @param evidenceRef reference document the finding rests on, if any
 * @param evidenceQuote verbatim passage from {@code evidenceRef} — verified by
 *     code before the finding may count
 */
record WonkoFinding(
        String key,
        String title,
        @Nullable String category,
        @Nullable String anchor,
        @Nullable String concern,
        QuestionWeight weight,
        String question,
        QuestionKind kind,
        List<String> options,
        @Nullable String proposal,
        @Nullable String dependsOn,
        @Nullable String context,
        boolean contradictsUser,
        @Nullable String reason,
        @Nullable String evidenceRef,
        @Nullable String evidenceQuote) {

    static final String CONCERN_CONTRADICTION = "contradiction";

    boolean isContradiction() {
        return CONCERN_CONTRADICTION.equals(concern);
    }
}
