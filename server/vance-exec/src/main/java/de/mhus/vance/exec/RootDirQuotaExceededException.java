package de.mhus.vance.exec;

/**
 * A write or create was refused because the workspace volume is past its hard
 * fill limit. The brain wraps it as {@code WorkspaceQuotaExceededException}.
 */
public class RootDirQuotaExceededException extends RootDirException {

    public RootDirQuotaExceededException(String message) {
        super(message);
    }
}
