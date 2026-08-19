package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Turn one entry into a project document — the only way transient feed content
 * becomes permanent.
 *
 * <p>The client sends the entry's own fields rather than an id: by the time
 * somebody clips, the page may be long gone from the source, and re-fetching to
 * clip what is already on screen would be the one place where a slow source
 * loses the reader their article.
 */
@GenerateTypeScript("centauri")
public record ClipRequest(
        String targetPath,
        String title,
        String url,
        @Nullable String publishedAt,
        @Nullable String summary,
        @Nullable String body,
        @Nullable String author,
        @Nullable String language,
        @Nullable String sourceId) {}
