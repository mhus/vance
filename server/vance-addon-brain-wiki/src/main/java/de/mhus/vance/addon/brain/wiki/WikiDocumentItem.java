package de.mhus.vance.addon.brain.wiki;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Slim document projection for the wiki top-nav search. */
@GenerateTypeScript("wiki")
public record WikiDocumentItem(
        String id,
        String path,
        @Nullable String title,
        @Nullable String kind,
        @Nullable String mimeType) {}
