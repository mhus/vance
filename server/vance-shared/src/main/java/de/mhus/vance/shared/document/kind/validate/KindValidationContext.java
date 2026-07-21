package de.mhus.vance.shared.document.kind.validate;

import org.jspecify.annotations.Nullable;

/**
 * Document-level validation context: identifies the document being checked
 * and exposes the read-only {@link DocRefs} facade for reference existence /
 * cross-kind checks. Block-level contexts (e.g. workbook's ValidationContext)
 * build on the same {@link Finding} / {@link DocRefs} vocabulary.
 *
 * <p>{@code mimeType} is the wire format of {@code content} (e.g.
 * {@code text/markdown}, {@code application/json}). Kind validators whose codec
 * is mime-driven (records, sheet, …) use it to pick the right parser; it is
 * {@code null} when unknown (pre-write content without a saved document), in
 * which case such a validator falls back to sniffing the content.
 */
public record KindValidationContext(
        String tenantId,
        String projectId,
        String docPath,
        @Nullable String mimeType,
        DocRefs docs) {}
