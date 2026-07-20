package de.mhus.vance.addon.brain.wiki;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTO returned by the wiki REST scan endpoint. */
@GenerateTypeScript("wiki")
public record WikiView(
        String folder,
        @Nullable String title,
        @Nullable String description,
        @Nullable String mainPagePath,
        @Nullable String mainPageId,
        @Nullable String indexPagePath,
        @Nullable String indexPageId,
        List<WikiSpaceView> spaces,
        List<WikiPageView> pages) {}
