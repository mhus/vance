package de.mhus.vance.brain.wonko;

import de.mhus.vance.api.question.QuestionWeight;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One topic of the review register — what was found, asked, answered or
 * decided about one concern of the subject. Remembered by {@link #key}, not by
 * the wording of a question, so a later round does not ask the same thing in
 * other words.
 */
@Data
@NoArgsConstructor
public class WonkoTopic {

    /** Hierarchical kebab key, e.g. {@code retry-policy/backoff}. */
    private String key = "";

    private String title = "";

    private @Nullable String category;

    /** Where in the subject the concern sits (section, heading, quote). */
    private @Nullable String anchor;

    /** contradiction | gap | vague | implausible | intent */
    private @Nullable String concern;

    private QuestionWeight weight = QuestionWeight.IMPORTANT;

    private WonkoTopicStatus status = WonkoTopicStatus.OPEN;

    /** Last question text asked about this topic. */
    private @Nullable String question;

    /** Wonko's own assumption, if it had one. */
    private @Nullable String proposal;

    private @Nullable String answer;

    private @Nullable String decision;

    private @Nullable String reason;

    private int askedRound;

    private int answeredRound;

    /** How often an answered topic was asked again because of a new contradiction. */
    private int reaskCount;
}
