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
 * Cached liveness verdict for an image URL. Lets the
 * {@code ImageValidatorService} skip the HTTP round-trip on URLs it
 * has recently checked — popular sources (Pixabay, Wikipedia, CDN
 * thumbnails) show up across many searches and revalidating them
 * each time would burn latency and bandwidth.
 *
 * <p>The {@link #url} field is the exact request URL — the same
 * string the LLM would emit in the final {@code ![alt](url)}
 * Markdown. No canonicalisation beyond what the original source
 * provides; a query-string difference is treated as a different
 * URL on purpose so cache poisoning via path-vs-query collisions
 * cannot happen.
 *
 * <p>Lifetime is enforced by a Mongo TTL index on {@link #expireAt}.
 * The writer chooses TTLs per outcome: longer for successful
 * validations (image stays put for a day-ish), shorter for failures
 * (server might come back after a maintenance blip).
 */
@Document(collection = "image_url_cache")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageUrlCacheDocument {

    @Id
    private @Nullable String id;

    @Indexed(unique = true)
    private String url = "";

    /**
     * Live verdict. {@code true} means the URL responded with an
     * image-typed payload (Content-Type starts with {@code image/}
     * or magic-number sniff matched); {@code false} means anything
     * else — wrong content-type, 4xx, 5xx, timeout, malformed.
     */
    private boolean ok;

    /**
     * Server-reported {@code Content-Type} (lowercased, parameters
     * stripped) for {@link #ok}=true. Useful for telemetry and for
     * downstream callers that want to pick an embed strategy per
     * mime-type (JPEG, SVG, GIF, …). May be empty when the verdict
     * came from a magic-number sniff (HEAD blocked, Range-GET probe).
     */
    private @Nullable String contentType;

    /**
     * Effective URL after redirects. May differ from {@link #url}
     * when the host redirected (CDN handoff, WordPress
     * {@code 301 → /}, https upgrade). When the path collapses to
     * {@code /} the writer also flips {@link #ok} to {@code false}
     * — homepage-fallback is not a valid image.
     */
    private @Nullable String finalUrl;

    /** Final HTTP status code after redirects, for diagnostics. */
    private int status;

    private @Nullable Instant validatedAt;

    /**
     * TTL anchor. Mongo deletes the row when this instant is in
     * the past. Set per write so {@code ok=true} and {@code ok=false}
     * can carry different lifetimes from the same code path.
     */
    @Indexed(expireAfter = "0s")
    private @Nullable Instant expireAt;
}
