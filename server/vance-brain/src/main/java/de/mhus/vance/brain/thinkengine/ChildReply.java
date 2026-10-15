package de.mhus.vance.brain.thinkengine;

import org.jspecify.annotations.Nullable;

/**
 * The envelope {@link ParentNotificationListener} puts around a child's last
 * reply in the parent event's summary — the marker lines tell a parent's
 * model what it is looking at; a parent relaying the reply to the user takes
 * only the text between them. One place for both sides, so the format cannot
 * drift between the listener and its readers (Arthur, Eddie, Wonko).
 */
public final class ChildReply {

    public static final String BEGIN = "--- BEGIN CHILD REPLY ---";
    public static final String END = "--- END CHILD REPLY ---";

    private ChildReply() {}

    /** {@code summary} with the child's verbatim reply appended inside the markers. */
    public static String wrap(String summary, String reply) {
        return summary + "\n\nLast assistant reply from this child (verbatim):\n" + BEGIN + "\n" + reply + "\n" + END;
    }

    /** The reply between the markers; the input unchanged when it carries none. */
    public static @Nullable String unwrap(@Nullable String summary) {
        if (summary == null) {
            return null;
        }
        int begin = summary.indexOf(BEGIN);
        if (begin < 0) {
            return summary;
        }
        int bodyStart = summary.indexOf('\n', begin);
        if (bodyStart < 0) {
            return summary;
        }
        int end = summary.indexOf(END, bodyStart);
        String inner = end < 0 ? summary.substring(bodyStart + 1) : summary.substring(bodyStart + 1, end);
        return inner.trim();
    }
}
