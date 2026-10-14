package de.mhus.vance.api.question;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/** How a single question is answered. */
@GenerateTypeScript("question")
public enum QuestionKind {
    /** Pick one of {@link QuestionDto#getOptions()} (free text allowed as override). */
    CHOICE,
    /** Free-text answer. */
    TEXT,
    /** Confirm or correct the asker's {@link QuestionDto#getProposal() proposal}. */
    CONFIRM
}
