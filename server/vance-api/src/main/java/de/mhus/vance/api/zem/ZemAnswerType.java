package de.mhus.vance.api.zem;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Kind of a single answer. Mirrors {@link ZemQuestionType} plus
 * {@link #REFUSAL}: no System-One provider returns one today, but
 * OpenAI's decision format does, and a bulk consumer must be able to
 * tell a refusal from an answer and from a failure without a later
 * wire break.
 */
@GenerateTypeScript("zem")
public enum ZemAnswerType {
    NOUL,
    CHOICE,
    SCORE,
    /** The model declined to answer this question. Carries no values. */
    REFUSAL
}
