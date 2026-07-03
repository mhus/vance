package de.mhus.vance.addon.brain.workbook.validate;

import org.jspecify.annotations.Nullable;

/**
 * Per-block validation context handed to each {@link BlockValidator}: the
 * {@code docPath} of the page being checked (base for reference resolution),
 * a human-readable {@code location} for findings, and the {@link DocRefs}
 * facade. Convenience {@link #resolve} parses a {@code vance:} reference
 * against this page's folder.
 */
public final class ValidationContext {

    private final String docPath;
    private final String location;
    private final DocRefs docs;

    public ValidationContext(String docPath, String location, DocRefs docs) {
        this.docPath = docPath;
        this.location = location;
        this.docs = docs;
    }

    public String docPath() {
        return docPath;
    }

    /** Human-readable location of the block being validated (for findings). */
    public String location() {
        return location;
    }

    public DocRefs docs() {
        return docs;
    }

    /** Parse a {@code vance:} reference against this page's folder. */
    public @Nullable VanceRef resolve(@Nullable String rawRef) {
        return VanceRef.parse(rawRef, docPath);
    }
}
