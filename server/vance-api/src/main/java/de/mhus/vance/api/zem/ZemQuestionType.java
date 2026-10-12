package de.mhus.vance.api.zem;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * The three System-One question types. Every provider seen so far
 * serves all three; the per-model differences are limits (option and
 * level counts, structured criteria), not missing types.
 */
@GenerateTypeScript("zem")
public enum ZemQuestionType {
    /** Yes/no — answered with {@code p(yes)}. */
    NOUL,
    /** Exactly one option out of 2..N — answered with the most likely
     *  option, the full distribution and a confidence. */
    CHOICE,
    /** Position on an ordered scale of 2..10 levels, index 0 lowest —
     *  answered with the expected level, the distribution and a
     *  confidence. */
    SCORE
}
