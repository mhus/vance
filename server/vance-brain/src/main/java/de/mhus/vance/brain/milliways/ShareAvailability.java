package de.mhus.vance.brain.milliways;

import org.jspecify.annotations.Nullable;

/**
 * Whether a handler can be used in a given scope, and if not, why.
 *
 * <p>{@code available == false} is a normal answer, not a failure: it is
 * how the UI learns to grey the entry out and what to write next to it. A
 * handler that cannot work must say so here rather than throwing from
 * {@link ShareHandler#share} later.
 */
public record ShareAvailability(boolean available, @Nullable String statusText) {

    private static final ShareAvailability READY = new ShareAvailability(true, null);

    public static ShareAvailability ready() {
        return READY;
    }

    /**
     * @param statusText what is missing, in one sentence, phrased for the
     *                   operator who could fix it
     */
    public static ShareAvailability unavailable(String statusText) {
        return new ShareAvailability(false, statusText);
    }
}
