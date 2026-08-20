package de.mhus.vance.brain.milliways;

/**
 * The handler exists but cannot be used in this scope — no SMTP pack in
 * the project, no other users in the tenant. Maps to 409: the handler list
 * the client saw was a snapshot, and this is the authoritative answer.
 */
public class ShareUnavailableException extends ShareException {

    public ShareUnavailableException(String message) {
        super(message);
    }
}
