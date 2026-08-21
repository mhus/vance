package de.mhus.vance.addon.brain.links;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /addon/links/entry}. Only {@code url} is required —
 * a link somebody pasted is a complete request, and the title is fetched
 * from the page when none is given.
 */
@GenerateTypeScript("links")
public record AddLinkRequest(
        String url,
        @Nullable String title,
        @Nullable String teaser,
        @Nullable String image,
        @Nullable String group,
        @Nullable List<String> tags,
        @Nullable String note) {}
