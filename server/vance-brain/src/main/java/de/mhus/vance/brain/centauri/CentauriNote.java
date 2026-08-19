package de.mhus.vance.brain.centauri;

import org.jspecify.annotations.Nullable;

/**
 * Why a stream is not represented in this page.
 *
 * <p>Structured rather than free text so the UI can say it in the reader's
 * language and distinguish "this source is off" from "this source just
 * failed". A page that silently omits a source looks like a source with no
 * news, which is a different statement entirely.
 */
public record CentauriNote(String sourceId, String selector, Kind kind, @Nullable String detail) {

    public enum Kind {

        /** No such endpoint is configured in this project. */
        UNKNOWN_SOURCE,

        /** Switched off via {@code centauri.endpoint.<id>.enabled}. */
        DISABLED,

        /** In a cooldown from an earlier failure. */
        COOLING_DOWN,

        /** Asked and failed — transport, bad response, refused request. */
        FAILED,

        /** Did not answer within the per-stream budget. */
        TIMED_OUT
    }
}
