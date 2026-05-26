package de.mhus.vance.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * OpenGraph preview for a remote URL. Returned by
 * {@code GET /brain/{tenant}/link-preview?url=&lt;...&gt;}.
 *
 * <p>{@link #ok} == {@code false} means the proxy could not produce
 * a useful preview — 4xx/5xx response, timeout, scheme not allowed,
 * or page returned no readable metadata. The Web-UI is expected to
 * render a muted "preview not available" card in that case rather
 * than swallowing the link.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("web")
public class LinkPreviewDto {

    private String url;

    private boolean ok;

    private @Nullable String title;

    private @Nullable String description;

    /** Absolute https URL of the preview image, if any. */
    private @Nullable String image;

    /** Display label for the source — og:site_name or hostname fallback. */
    private @Nullable String siteName;

    /** og:type, lowercased. "website", "article", "video.other", "profile" … */
    private @Nullable String type;

    /** URL after redirects — may differ from request URL. */
    private @Nullable String finalUrl;

    private int status;

    /** Failure reason when {@link #ok} is false, free-text. */
    private @Nullable String failureReason;
}
