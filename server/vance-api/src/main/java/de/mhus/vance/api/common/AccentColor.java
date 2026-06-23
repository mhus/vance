package de.mhus.vance.api.common;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Restricted accent-color palette shared by entities that show an accent
 * in the UI (sessions, documents, …).
 *
 * <p>The 12 values map to Tailwind color names; concrete hex values
 * are picked by the theme (light/dark variants live there, not here).
 * Keeping the set small prevents UIs from looking like a free-form
 * palette and lets LLM auto-suggesters pick from a known vocabulary.
 */
@GenerateTypeScript("common")
public enum AccentColor {
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
