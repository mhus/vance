package de.mhus.vance.toolpack.research;

import org.jspecify.annotations.Nullable;

/**
 * One hit the evaluate-recipe rejected. Surfaced separately in the
 * {@code research_investigate} result so the caller can see what was
 * looked at and what was dropped, with the recipe's stated reason.
 */
public record DroppedHit(
        String title,
        String url,
        SearchModality modality,
        String providerInstanceId,
        @Nullable String dropReason) {

    public DroppedHit {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        if (modality == null) {
            throw new IllegalArgumentException("modality is required");
        }
        if (providerInstanceId == null || providerInstanceId.isBlank()) {
            throw new IllegalArgumentException("providerInstanceId is required");
        }
    }
}
