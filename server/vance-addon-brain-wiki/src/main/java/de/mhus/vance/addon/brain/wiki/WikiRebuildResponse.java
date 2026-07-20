package de.mhus.vance.addon.brain.wiki;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO returned by the wiki rebuild endpoint. */
@GenerateTypeScript("wiki")
public record WikiRebuildResponse(
        String folder,
        String rootIndexPath,
        @Nullable String backlinksPath,
        int indexCount,
        int pageCount,
        int spaceCount) {}
