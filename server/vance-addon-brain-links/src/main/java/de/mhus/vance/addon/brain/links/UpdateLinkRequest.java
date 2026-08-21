package de.mhus.vance.addon.brain.links;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PATCH /addon/links/entry}.
 *
 * <p>A field left {@code null} is not touched; a blank string clears it.
 * The distinction is the whole point of a PATCH here — moving one link
 * between groups must not drop the teaser written for it. A blank
 * {@code title} is the one special case: it re-derives the snapshot from
 * the page instead of clearing it to nothing.
 */
@GenerateTypeScript("links")
public record UpdateLinkRequest(
        String url,
        @Nullable String title,
        @Nullable String teaser,
        @Nullable String image,
        @Nullable String group,
        @Nullable List<String> tags,
        @Nullable String note) {}
