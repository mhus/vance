package de.mhus.vance.api.recipes;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Where the effective copy of a recipe lives. Reported by the
 * "effective recipes" admin endpoint so the UI can mark inherited vs.
 * locally-overridden entries and decide whether to offer "edit" or
 * "override here".
 */
@GenerateTypeScript("recipes")
public enum RecipeSource {
    /** From the bundled {@code recipes.yaml} on the brain's classpath. */
    BUNDLED,
    /** A tenant-scope override stored in MongoDB. */
    TENANT,
    /** A project-scope override stored in MongoDB. */
    PROJECT
}
