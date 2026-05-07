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
 * Per-origin cache entry for an {@code /llms.txt} probe.
 *
 * <p>The {@link #origin} key is the canonicalised
 * {@code <scheme>://<host>[:<port>]} triple — never the full URL, and
 * never including a trailing slash. Two URLs sharing an origin share
 * one cache row.
 *
 * <p>Lifetime: Mongo's TTL monitor deletes a row once {@code now()
 * >= expireAt}. The writer chooses {@link #expireAt} based on
 * {@link #status} (longer for {@link OverviewStatus#OK} /
 * {@link OverviewStatus#NOT_FOUND}, very short for
 * {@link OverviewStatus#ERROR}).
 */
@Document(collection = "web_origin_overviews")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebOriginOverviewDocument {

    @Id
    private @Nullable String id;

    @Indexed(unique = true)
    private String origin = "";

    private OverviewStatus status = OverviewStatus.ERROR;

    /**
     * Body of {@code /llms.txt} when {@link #status} is
     * {@link OverviewStatus#OK}. Already truncated by the writer to
     * the configured probe budget; {@link #contentLength} keeps the
     * pre-truncation size for diagnostics.
     */
    private @Nullable String content;

    /** Original (pre-truncation) byte/character length of the response body. */
    private int contentLength;

    private @Nullable Instant fetchedAt;

    /**
     * TTL anchor. Mongo deletes the row when this instant is in the
     * past. Set per write, so OK / NOT_FOUND / ERROR can carry
     * different lifetimes from the same code path.
     */
    @Indexed(expireAfter = "0s")
    private @Nullable Instant expireAt;
}
