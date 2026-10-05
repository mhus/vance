package de.mhus.vance.toolpack.research;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of a single instance's {@link SearchProviderInstance#search}
 * call. The dispatcher returns the first {@link #ok()} result of its
 * cascade; if every instance fails or returns a soft-failure result,
 * the dispatcher returns the last soft-failure (or builds an
 * unavailable result if nothing was even tried).
 *
 * <p>{@code upstreamHeaders} carries protocol-level response headers
 * the dispatcher and {@code AgrajagChecker} can inspect — most
 * importantly {@code Retry-After} for {@code FROM_RETRY_AFTER} cooldown
 * resolution. Map is case-insensitive lookup at the call site by
 * convention; protocols normalise keys to lowercase.
 */
public record SearchResult(
        String query,
        SearchModality modality,
        String providerInstanceId,
        SearchTier tier,
        List<SearchHit> hits,
        int returnedCount,
        int droppedCount,
        @Nullable String note,
        @Nullable String errorMessage,
        Map<String, String> upstreamHeaders) {

    public SearchResult {
        if (query == null) {
            throw new IllegalArgumentException("query is required");
        }
        if (modality == null) {
            throw new IllegalArgumentException("modality is required");
        }
        if (providerInstanceId == null || providerInstanceId.isBlank()) {
            throw new IllegalArgumentException("providerInstanceId is required");
        }
        if (tier == null) {
            throw new IllegalArgumentException("tier is required");
        }
        hits = hits == null ? List.of() : List.copyOf(hits);
        upstreamHeaders = upstreamHeaders == null ? Map.of() : Map.copyOf(upstreamHeaders);
    }

    public boolean ok() {
        return errorMessage == null;
    }

    /**
     * Build a synthetic "no provider could serve this" result the
     * dispatcher returns when none of the candidate instances passed
     * the availability / cooldown / quota gate.
     */
    public static SearchResult unavailable(SearchRequest req, String message) {
        return new SearchResult(
                req.query(), req.modality(), "(none)", req.tier(), List.of(), 0, 0, null, message, Map.of());
    }

    /**
     * Build a soft-failure result: the instance answered but cannot serve
     * this request — unsupported modality, a configuration state, a
     * malformed payload. {@code errorMessage} is set, {@code hits} is empty,
     * and the dispatcher cascades on to the next candidate: no cooldown, no
     * health write.
     */
    public static SearchResult softFailure(SearchRequest req, String instanceId, String message) {
        return new SearchResult(
                req.query(), req.modality(), instanceId, req.tier(), List.of(), 0, 0, null, message, Map.of());
    }
}
