package de.mhus.vance.api.question;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * How much a question matters — three steps on purpose: numeric scores from a
 * model are noisy, three steps are not.
 */
@GenerateTypeScript("question")
public enum QuestionWeight {
    /** Contradiction, missing core fact, likely error. */
    BLOCKER,
    /** Relevant gap or ambiguity. */
    IMPORTANT,
    /** Wording, cosmetics. */
    MINOR
}
