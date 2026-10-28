package de.mhus.vance.exec;

/**
 * A file operation inside a RootDir failed or was refused: path escape, missing
 * file, I/O error. Plain-Java twin of the brain's {@code WorkspaceException}, which
 * wraps it at the Spring boundary; the runner maps it to a protocol error.
 */
public class RootDirException extends RuntimeException {

    public RootDirException(String message) {
        super(message);
    }

    public RootDirException(String message, Throwable cause) {
        super(message, cause);
    }
}
