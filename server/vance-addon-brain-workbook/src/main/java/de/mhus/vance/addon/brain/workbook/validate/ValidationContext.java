package de.mhus.vance.addon.brain.workbook.validate;

import org.jspecify.annotations.Nullable;

/**
 * Per-document validation context handed to each {@link BlockValidator}: the
 * {@code docPath} of the page being checked (base for reference resolution)
 * plus the {@link DocRefs} facade. Convenience {@link #resolve} parses a
 * {@code vance:} fence reference against this page's folder.
 */
public final class ValidationContext {

    private final String docPath;
    private final DocRefs docs;

    public ValidationContext(String docPath, DocRefs docs) {
        this.docPath = docPath;
        this.docs = docs;
    }

    public String docPath() {
        return docPath;
    }

    public DocRefs docs() {
        return docs;
    }

    /** Parse a {@code vance:} reference against this page's folder. */
    public @Nullable VanceRef resolve(@Nullable String rawRef) {
        return VanceRef.parse(rawRef, docPath);
    }
}
