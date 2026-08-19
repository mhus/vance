package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One entry as the reader sees it. {@code publishedAt} is an ISO-8601 instant;
 * the client formats it, since only the browser knows the reader's timezone.
 *
 * <p>{@code controlUrl} is the source's own UI for this entry and is only ever
 * populated when the source declared it and the URL survived validation.
 */
@GenerateTypeScript("centauri")
public record FeedItemView(
        String id,
        String publishedAt,
        String title,
        String url,
        @Nullable String summary,
        @Nullable String author,
        @Nullable String language,
        @Nullable String imageUrl,
        @Nullable String controlUrl,
        List<String> tags,
        String sourceId,
        String sourceDisplayName,
        String selector) {}
