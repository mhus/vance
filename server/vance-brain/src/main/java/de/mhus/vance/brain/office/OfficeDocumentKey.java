package de.mhus.vance.brain.office;

import de.mhus.vance.shared.document.DocumentDocument;

/**
 * The {@code documentKey} ONLYOFFICE caches its server-side editor
 * state under. Rule: any time the document content changes
 * externally, the key must change so the server re-fetches via
 * {@code /api/office/download/...} instead of serving stale bytes.
 *
 * <p>Format: {@code <docId>-v<version>}. Vance bumps the version
 * field on every save through {@code DocumentService.create} /
 * {@code .updateInline} / {@code .updateBytes} via optimistic
 * locking, so the key flips automatically whenever the document
 * changes — regardless of whether it was edited via the office
 * server or rewritten externally (e.g. by
 * {@code report_from_markdown}).
 */
public final class OfficeDocumentKey {

    private OfficeDocumentKey() {}

    /** Build the cache key from the loaded document. */
    public static String of(DocumentDocument doc) {
        return build(doc.getId(), doc.getVersion());
    }

    /** Visible-for-testing variant. */
    static String build(String docId, Long version) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId required");
        }
        long v = version == null ? 0L : version;
        return docId + "-v" + v;
    }
}
