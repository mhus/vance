package de.mhus.vance.addon.brain.finance.model;

import org.jspecify.annotations.Nullable;

/**
 * A full {@code kind: finance-tree} document — format {@code version},
 * display metadata and the single {@link FinanceNode} {@code root}
 * (may be {@code null} for a fresh/empty document).
 *
 * <p>{@code version} is always set on disk (v1: {@code 1}) so future format
 * migrations can key off it; a missing version is read as {@code 1}.
 */
public record FinanceTreeDocument(
        int version,
        @Nullable String title,
        @Nullable String description,
        @Nullable FinanceNode root) {

    /** Current on-disk format version. */
    public static final int CURRENT_VERSION = 1;

    public static FinanceTreeDocument empty(@Nullable String title,
                                            @Nullable String description) {
        return new FinanceTreeDocument(CURRENT_VERSION, title, description, null);
    }
}
