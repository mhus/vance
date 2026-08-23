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

        /** Switched off via {@code enabled: false} in the source document. */
        DISABLED,

        /** In a cooldown from an earlier failure. */
        COOLING_DOWN,

        /** Asked and failed — transport, bad response, refused request. */
        FAILED,

        /** Did not answer within the per-stream budget. */
        TIMED_OUT,

        /**
         * The selector is not one this source can read — {@code detail} carries
         * the source's own complaint, in words meant for a person.
         *
         * <p>Only free-text ({@code FREEFORM}) sources can produce this, and
         * they are the only ones that need it: a hashtag typed with a trailing
         * space, a {@code #} that should not be there, an invented scope. Until
         * this existed such a stream simply came back empty, which reads as
         * „nothing was posted" — a different statement entirely, and the one
         * thing a reader cannot tell from a typo.
         */
        INVALID_SELECTOR,

        /**
         * Left out because the reader selected a facet this source does not
         * declare — {@code detail} names the keys.
         *
         * <p>Not a failure and not a filter result: the source was never
         * asked. Shown because „source X is not part of this selection" is
         * information, and a silently shorter timeline is not.
         */
        MISSING_FACET
    }
}
