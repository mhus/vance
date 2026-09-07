package de.mhus.vance.age;

/**
 * Base of every failure this facade throws. Unchecked — callers (foot view
 * decryption, brain tools) translate it into their own surface (dialog
 * message, tool error payload) rather than declaring crypto plumbing
 * through their signatures.
 */
public class AgeCryptoException extends RuntimeException {

    public AgeCryptoException(String message) {
        super(message);
    }

    public AgeCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
