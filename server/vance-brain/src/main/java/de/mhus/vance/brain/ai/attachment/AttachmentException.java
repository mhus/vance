package de.mhus.vance.brain.ai.attachment;

/**
 * Thrown by {@link AttachmentResolver} when an attachment cannot be
 * loaded for an LLM call: missing document, foreign-project access,
 * unsupported MIME type, exceeded size limit. Engines should let
 * this propagate so the user sees a clear error rather than a
 * silently-dropped attachment.
 */
public class AttachmentException extends RuntimeException {

    public AttachmentException(String message) {
        super(message);
    }

    public AttachmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
