package de.mhus.vance.shared.web;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Cached OpenGraph preview for a remote URL — backing store for the
 * Slack-style link cards the Web-UI renders for every external link
 * the LLM emits.
 *
 * <p>Shared across the tenant — OG-tags are public metadata, there
 * is nothing user- or project-specific about them. Two different
 * tenants asking for the same URL hit the same cache row.
 *
 * <p>Lifetime is enforced by a Mongo TTL index on {@link #expireAt}.
 * The writer chooses TTLs per outcome: longer for successful previews
 * (most pages stay put for a week-ish), shorter for failures so a
 * site that briefly 5xx'd doesn't stay "broken" forever.
 */
@Document(collection = "link_preview_cache")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkPreviewCacheDocument {

    @Id
    private @Nullable String id;

    @Indexed(unique = true)
    private String url = "";

    /**
     * Verdict — {@code true} when the writer extracted at least one
     * useful piece of metadata (title or description) from a 2xx
     * response. {@code false} for 4xx/5xx, timeouts, schemes the
     * proxy refuses, or pages that returned no readable metadata.
     */
    private boolean ok;

    private @Nullable String title;
    private @Nullable String description;

    /** Absolute URL to the preview image (og:image / twitter:image). */
    private @Nullable String image;

    /** Display label for the source (og:site_name, or hostname fallback). */
    private @Nullable String siteName;

    /** og:type, lowercased — "website", "article", "video.other", etc. Optional. */
    private @Nullable String type;

    /**
     * Final URL after redirects. Lets the UI flag links that landed
     * somewhere unexpected (login wall, geo-redirect) and keeps the
     * card consistent with what an actual click would resolve to.
     */
    private @Nullable String finalUrl;

    /** Final HTTP status code for diagnostics. */
    private int status;

    /** Free-text reason when {@link #ok} is false. */
    private @Nullable String failureReason;

    private @Nullable Instant fetchedAt;

    @Indexed(expireAfter = "0s")
    private @Nullable Instant expireAt;
}
