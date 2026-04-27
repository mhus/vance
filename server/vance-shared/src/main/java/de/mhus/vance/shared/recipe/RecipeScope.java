package de.mhus.vance.shared.recipe;

/**
 * Cascade level a {@link RecipeDocument} lives at. Resolution walks
 * Project → Tenant; bundled defaults from the YAML resource live
 * outside Mongo and aren't represented here.
 */
public enum RecipeScope {
    /** Tenant-wide override / addition. */
    TENANT,
    /** Project-scoped override / addition — beats {@link #TENANT}. */
    PROJECT
}
