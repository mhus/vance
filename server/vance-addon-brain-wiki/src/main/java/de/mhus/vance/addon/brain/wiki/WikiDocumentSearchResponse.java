package de.mhus.vance.addon.brain.wiki;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/** Wire DTO returned by the wiki document-search endpoint. */
@GenerateTypeScript("wiki")
public record WikiDocumentSearchResponse(
        List<WikiDocumentItem> items,
        long total) {}
