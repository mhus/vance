package de.mhus.vance.age;

/**
 * A valid age file, but none of the provided identities or passphrases
 * unwrapped its file key. The user-facing answer is "import the right
 * key", not "file is broken".
 */
public class AgeNoKeyMatchException extends AgeCryptoException {

    public AgeNoKeyMatchException(String message) {
        super(message);
    }

    public AgeNoKeyMatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
