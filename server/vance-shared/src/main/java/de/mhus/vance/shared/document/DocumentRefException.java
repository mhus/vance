package de.mhus.vance.shared.document;

/**
 * Thrown by {@link DocumentRefResolver} when a reference cannot be
 * resolved to a {@code (projectId, path)} pair — blank ref, blank
 * authority, or a {@code ..} segment that escapes above the project
 * root. Deterministic (no I/O, no LLM); the resolver never touches the
 * document store, so this signals a malformed reference, not a missing
 * document.
 */
public class DocumentRefException extends RuntimeException {

    public DocumentRefException(String message) {
        super(message);
    }
}
