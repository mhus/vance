package de.mhus.vance.addon.brain.wiki;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/** Wire DTO for one page inside a wiki. */
@GenerateTypeScript("wiki")
public record WikiPageView(
        String id,
        String path,
        String relativePath,
        String space,
        String slug,
        String title,
        boolean main) {}
