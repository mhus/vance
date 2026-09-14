package de.mhus.vance.brain.recipe;

import de.mhus.vance.shared.project.ProjectKind;

/**
 * Which project kind a recipe belongs to — the picker filter behind
 * {@code projectKind:} in the recipe YAML.
 *
 * <p>Hub projects ({@link ProjectKind#SYSTEM} — {@code _user_*},
 * {@code _tenant}) and regular projects live in different worlds: the hub
 * chat is always Eddie ({@code SessionChatBootstrapper} forces the hub
 * engine and ignores recipe overrides), regular projects run Arthur and
 * worker recipes. A recipe that appears in the wrong picker is a lie the
 * user can click: picking Arthur in the hub silently yields Eddie, picking
 * Eddie in a regular project spawns a hub engine without a hub.
 *
 * <p>Default is {@link #NORMAL} — every recipe written before the field
 * existed is a regular-project recipe. {@link #ANY} is the explicit
 * opt-out for a recipe that genuinely works in both worlds.
 */
public enum RecipeProjectKind {

    /** Pickable only in regular ({@link ProjectKind#NORMAL}) projects — the default. */
    NORMAL,

    /** Pickable only in SYSTEM hub projects (Eddie's home, {@code _user_*} / {@code _tenant}). */
    SYSTEM,

    /** Pickable in both project kinds. */
    ANY;

    /**
     * Whether a recipe of this kind may be offered for a project of the
     * given {@link ProjectKind}.
     */
    public boolean allowedIn(ProjectKind project) {
        if (this == ANY) return true;
        return project == ProjectKind.SYSTEM ? this == SYSTEM : this == NORMAL;
    }
}
