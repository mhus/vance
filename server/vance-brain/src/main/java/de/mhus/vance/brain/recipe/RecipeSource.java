package de.mhus.vance.brain.recipe;

/**
 * Where the resolver picked the recipe up. Cascade priority is
 * {@link #PROJECT} → {@link #TENANT} → {@link #BUNDLED}.
 */
public enum RecipeSource {
    BUNDLED,
    TENANT,
    PROJECT
}
