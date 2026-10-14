package de.mhus.vance.api.question;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Per-question outcome. Besides a real answer every question always offers the
 * three standard exits as fixed choices, so the asker never has to interpret
 * free text to learn that the user does not want to go deeper.
 */
@GenerateTypeScript("question")
public enum QuestionOutcome {
    /** A substantive answer is in {@link QuestionAnswerDto#getValue()}. */
    ANSWERED,
    /** "Good as it is" — the topic is closed, no further detail wanted. */
    SETTLED,
    /** Not now — may be asked again later. */
    LATER,
    /** The user does not know. */
    UNKNOWN
}
