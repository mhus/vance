package de.mhus.vance.api.session;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Restricted accent-color palette for session-metadata visuals.
 *
 * <p>The 12 values map to Tailwind color names; concrete hex values
 * are picked by the theme (light/dark variants live there, not here).
 * Keeping the set small prevents the session list from looking like
 * a free-form palette and lets the LLM auto-suggester pick from a
 * known vocabulary.
 *
 * <p>See {@code specification/session-lifecycle.md} §14.
 */
@GenerateTypeScript("session")
public enum SessionColor {
    SLATE,
    RED,
    ORANGE,
    AMBER,
    GREEN,
    TEAL,
    CYAN,
    BLUE,
    INDIGO,
    PURPLE,
    PINK,
    ROSE
}
