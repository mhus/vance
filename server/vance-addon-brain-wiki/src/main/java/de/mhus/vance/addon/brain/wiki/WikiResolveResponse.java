package de.mhus.vance.addon.brain.wiki;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTO for {@code GET /resolve} — a {@code [[Wikilink]]} resolution.
 * When {@code exists} is false the link is "red"; {@code createPath} is the
 * path a red-link create would write to. {@code ambiguous} flags a
 * first-match resolution across multiple same-slug pages.
 */
@GenerateTypeScript("wiki")
public record WikiResolveResponse(
        String target,
        boolean exists,
        boolean ambiguous,
        String slug,
        @Nullable String path,
        @Nullable String id,
        @Nullable String space,
        String createSpace,
        String createPath) {}
