package de.mhus.vance.addon.brain.contacts;

import org.jspecify.annotations.Nullable;

/** Gravatar could not be asked — network, timeout or an unexpected status. Not "no avatar". */
public class GravatarUnavailableException extends RuntimeException {

    public GravatarUnavailableException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
