package de.mhus.vance.brain.recipe;

/**
 * Cascade layer that produced a recipe lookup result. Mirrors
 * {@link de.mhus.vance.shared.document.LookupResult.Source} since
 * recipes are persisted as documents under {@code recipes/<name>.yaml}.
 *
 * <p>Cascade priority is {@link #PROJECT} → {@link #VANCE} →
 * {@link #RESOURCE}.
 */
public enum RecipeSource {
    /** From the user's project under {@code recipes/<name>.yaml}. Innermost. */
    PROJECT,
    /** From the tenant-wide {@code _vance} system project. */
    VANCE,
    /** From the bundled classpath resource {@code vance-defaults/recipes/<name>.yaml}. */
    RESOURCE
}
