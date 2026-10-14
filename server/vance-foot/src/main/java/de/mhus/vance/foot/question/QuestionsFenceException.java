package de.mhus.vance.foot.question;

/** A {@code questions} body the client cannot render — the message says what is wrong. */
public class QuestionsFenceException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public QuestionsFenceException(String message) {
        super(message);
    }

    public QuestionsFenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
