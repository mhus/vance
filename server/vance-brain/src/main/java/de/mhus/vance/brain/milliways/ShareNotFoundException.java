package de.mhus.vance.brain.milliways;

/** The named handler or the named document does not exist. Maps to 404. */
public class ShareNotFoundException extends ShareException {

    public ShareNotFoundException(String message) {
        super(message);
    }
}
