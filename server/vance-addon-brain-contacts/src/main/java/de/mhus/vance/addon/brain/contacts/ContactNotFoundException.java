package de.mhus.vance.addon.brain.contacts;

/** No card with that id in the scopes that were searched. */
public class ContactNotFoundException extends RuntimeException {

    public ContactNotFoundException(String message) {
        super(message);
    }
}
