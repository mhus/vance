package de.mhus.vance.shared.workspace;

/** The stored git credentials cannot be applied to a repository, and guessing would be wrong. */
public class GitCredentialException extends RuntimeException {

    public GitCredentialException(String message) {
        super(message);
    }
}
