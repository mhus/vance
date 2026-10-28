package de.mhus.vance.exec;

/** A {@link GitOperations} call failed; the message is the backend's own. */
public class GitOperationException extends RootDirException {

    public GitOperationException(String message) {
        super(message);
    }

    public GitOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
