package de.mhus.vance.brain.tools.kinds;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Budget for the multi-document scan tools (the one doc_* cost class that is
 * unbounded on its own): each scanned document is one storage fetch, so a
 * scan over a whole project is O(number of documents), latency-dominated —
 * and a pattern with few matches scans <em>every</em> document fully.
 *
 * <p>The budget bounds that scan on two axes before any body is read (the
 * per-document check runs on the {@code size} metadata, not on the fetched
 * body):
 *
 * <ul>
 *   <li>{@link #MAX_DOC_BYTES} — single documents above the cap are skipped
 *       outright and counted as {@code skippedOversized}, mirroring
 *       {@code file_grep}'s {@code MAX_FILE_BYTES}. A single 50-MB document
 *       would otherwise dominate a scan and spike the heap three- to
 *       fourfold when split into lines.</li>
 *   <li>{@code maxScannedDocs} (default {@link #DEFAULT_MAX_SCANNED_DOCS},
 *       caller-raizable via the {@code maxScannedDocs} param) plus a total
 *       byte budget — whichever hits first stops the scan with
 *       {@code truncated=true} and a warning that tells the caller the results
 *       may be incomplete and how to narrow.</li>
 * </ul>
 *
 * <p>Why a scanned-document cap and not a path-depth cap: document paths are
 * a shallow virtual tree — a single top folder can hold ten thousand
 * documents. Depth protects against {@code node_modules}-style trees, not
 * against virtual folders.
 *
 * <p>Stopping is honest, not silent: the tool reports {@code scannedDocuments},
 * the candidate count and a {@code warning} string, so the model can decide
 * whether to narrow the scope and scan the rest (see
 * {@code planning/doc-file-tool-parity.md} §3.3).
 */
public final class DocScanBudget {

    public static final int DEFAULT_MAX_SCANNED_DOCS = 500;
    public static final int MAX_SCANNED_DOCS_PARAM_CAP = 2000;
    /** Per-document cap — same value as file_grep's MAX_FILE_BYTES. */
    public static final long MAX_DOC_BYTES = 2L * 1024 * 1024;

    public static final long DEFAULT_TOTAL_BYTES = 50L * 1024 * 1024;

    private final int maxScannedDocs;
    private final long maxTotalBytes;

    private int scannedDocs;
    private long totalBytes;
    private int skippedOversized;
    private @Nullable StopReason stopReason;

    private enum StopReason {
        DOC_COUNT,
        BYTE_BUDGET
    }

    private DocScanBudget(int maxScannedDocs, long maxTotalBytes) {
        this.maxScannedDocs = maxScannedDocs;
        this.maxTotalBytes = maxTotalBytes;
    }

    /**
     * From the tool params: {@code maxScannedDocs} is optional, defaults to
     * {@link #DEFAULT_MAX_SCANNED_DOCS} and is clamped to
     * {@link #MAX_SCANNED_DOCS_PARAM_CAP} — the cap exists to keep a single
     * call bounded, and a caller who really needs more scans in slices.
     */
    public static DocScanBudget fromParams(@Nullable Map<String, Object> params) {
        Integer requested = KindToolSupport.paramInt(params, "maxScannedDocs");
        int n = requested == null
                ? DEFAULT_MAX_SCANNED_DOCS
                : Math.min(MAX_SCANNED_DOCS_PARAM_CAP, Math.max(1, requested));
        return new DocScanBudget(n, DEFAULT_TOTAL_BYTES);
    }

    /** Schema fragment for the {@code maxScannedDocs} param, shared by the scan tools. */
    public static Map<String, Object> maxScannedDocsProperty() {
        return Map.of(
                "type",
                "integer",
                "description",
                "Cap on how many documents this call reads and scans. Default "
                        + DEFAULT_MAX_SCANNED_DOCS + ", max " + MAX_SCANNED_DOCS_PARAM_CAP
                        + ". When the cap stops the scan, the result carries truncated=true and a "
                        + "warning — narrow the pathPrefix to scan the rest.");
    }

    /**
     * Whether this document may be read and scanned. Must be called with the
     * document's {@code size} metadata <em>before</em> fetching the body, so
     * the caps cost nothing. A document above {@link #MAX_DOC_BYTES} is
     * skipped (counted, scan continues); an exhausted budget stops the scan
     * ({@link #exhausted()} turns true).
     */
    public boolean tryClaim(long docSize) {
        if (docSize > MAX_DOC_BYTES) {
            skippedOversized++;
            return false;
        }
        if (scannedDocs >= maxScannedDocs) {
            stopReason = StopReason.DOC_COUNT;
            return false;
        }
        if (totalBytes + docSize > maxTotalBytes) {
            stopReason = StopReason.BYTE_BUDGET;
            return false;
        }
        scannedDocs++;
        totalBytes += docSize;
        return true;
    }

    /** Whether the scan was stopped by a budget — the tool must say so. */
    public boolean exhausted() {
        return stopReason != null;
    }

    public int scannedDocs() {
        return scannedDocs;
    }

    public int skippedOversized() {
        return skippedOversized;
    }

    /**
     * The result {@code warning} for a budget-stopped scan. Null when the
     * scan ran to completion — a warning without a stop would be noise, and
     * the regular {@code truncated} flag covers the match-limit case.
     */
    public @Nullable String warning(int totalCandidates, String scope) {
        if (stopReason == null) return null;
        String reason = stopReason == StopReason.DOC_COUNT
                ? "reached the maxScannedDocs cap of " + maxScannedDocs
                : "reached the " + (maxTotalBytes / (1024 * 1024)) + " MB scan budget";
        return "scan stopped early (" + reason + ") after " + scannedDocs + " of " + totalCandidates
                + " candidate documents under '" + scope + "' — results may be incomplete; "
                + "narrow the pathPrefix to scan the rest in slices";
    }
}
