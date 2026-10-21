package de.mhus.vance.shared.document;

/**
 * Sum of {@code size} and row count over one slice of a document
 * collection — the shape the storage breakdown (Insights → Quotas) reads
 * from {@link DocumentService} and {@link DocumentArchiveService}.
 */
public record SizeTotals(long bytes, long count) {

    public static final SizeTotals EMPTY = new SizeTotals(0L, 0L);
}
