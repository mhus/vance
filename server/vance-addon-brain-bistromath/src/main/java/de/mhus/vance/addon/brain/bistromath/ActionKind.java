package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * What an event handler in a view document does.
 *
 * <p>The set is closed on purpose. A handler is written as one string in the
 * view YAML and parsed here into a kind plus its parts, so the client never
 * sees an unparsed handler expression — an unrecognised one is rejected where
 * the document is read, not where it is clicked.
 */
@GenerateTypeScript("bistromath")
public enum ActionKind {

    /** {@code navigate:<handle>} — open another view of this app. */
    NAVIGATE,

    /** {@code reload} — re-read the current view and its tables. */
    RELOAD,

    /** {@code <script-ref>:<function>} — call a top-level function of the program. */
    SCRIPT
}
