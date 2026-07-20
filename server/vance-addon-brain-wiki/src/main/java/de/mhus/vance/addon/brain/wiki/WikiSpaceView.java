package de.mhus.vance.addon.brain.wiki;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTO for one space (sub-folder) of a wiki. {@code name} is the
 * space path relative to the wiki root (empty string = root). {@code main}
 * / {@code index} carry the space's curated home and generated index
 * documents when present.
 */
@GenerateTypeScript("wiki")
public record WikiSpaceView(
        String name,
        String title,
        int pageCount,
        @Nullable String mainPath,
        @Nullable String mainId,
        @Nullable String indexPath,
        @Nullable String indexId) {}
