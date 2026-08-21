package de.mhus.vance.addon.brain.links;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Outcome of {@code POST /addon/links/rebuild} — the generated index. */
@GenerateTypeScript("links")
public record LinksRebuildResponse(
        String folder,
        String path,
        @Nullable String markdownLink,
        int entryCount,
        int groupCount) {}
