package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import java.util.Map;
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
        /**
         * The full text, when this entry came from a single-entry lookup.
         * Null in a page: that is the teaser, and the body is what makes a
         * detail worth fetching.
         */
        @Nullable String body,
        /**
         * The source's own fields, as it wrote them — a place name, a word
         * count, which feeds delivered a deduplicated article. Untyped by
         * design and for display only: a filter over keys nobody declared
         * means something different at every source.
         */
        Map<String, Object> extras,
        String sourceId,
        String sourceDisplayName,
        String selector) {}
