package de.mhus.vance.addon.brain.zarniwoop;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One result, as a person's screen needs it.
 *
 * <p>Deliberately wider than what the LLM path sends. Two differences, both for
 * the same reason — characters are free in a browser and expensive in a prompt:
 * <ul>
 *   <li>{@code body} is <b>not</b> truncated here. The tool path caps it at a
 *       thousand characters because ten abstracts would cost thousands of tokens;
 *       a detail panel has no such budget.
 *   <li>{@code contentId} and friends are exposed, so the surface can offer
 *       "load the full text" for a source that has one — and, just as
 *       importantly, <i>not</i> offer it where none exists.
 * </ul>
 *
 * @param url          where the hit lives. For an image this is the <b>page</b>,
 *                     while {@code extras.imageUrl} is the file — confusing them
 *                     costs the viewer the context and the attribution.
 * @param body         the source's own text when it ships one (paper abstract,
 *                     encyclopedia extract). Full length.
 * @param contentId    identifier for the content endpoint; null when this hit has
 *                     nothing further to fetch.
 * @param contentState {@code embedded} (body is right here), {@code on-demand}
 *                     (fetchable via the content endpoint), or {@code none}. The
 *                     surface reads this rather than guessing, so a "load full
 *                     text" button never appears where it would fail.
 * @param sizeBytes    size of the full body where the source declared one, so a
 *                     person can decide before paying for the fetch. Null when
 *                     unknown.
 * @param extras       per-modality fields exactly as the provider produced them
 *                     (imageUrl, thumbnailUrl, doi, citedByCount, videoId, …).
 */
@GenerateTypeScript("search")
public record SearchHitView(
        String title,
        String url,
        @Nullable String snippet,
        @Nullable String source,
        String modality,
        @Nullable String body,
        @Nullable String contentId,
        String contentState,
        @Nullable String mimeType,
        @Nullable Long sizeBytes,
        Map<String, Object> extras) {

    /** Values of {@link #contentState()}. */
    public static final String CONTENT_EMBEDDED = "embedded";
    public static final String CONTENT_ON_DEMAND = "on-demand";
    public static final String CONTENT_NONE = "none";
}
