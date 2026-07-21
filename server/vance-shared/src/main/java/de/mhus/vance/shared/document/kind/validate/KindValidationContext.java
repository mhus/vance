package de.mhus.vance.shared.document.kind.validate;

/**
 * Document-level validation context: identifies the document being checked
 * and exposes the read-only {@link DocRefs} facade for reference existence /
 * cross-kind checks. Block-level contexts (e.g. workbook's ValidationContext)
 * build on the same {@link Finding} / {@link DocRefs} vocabulary.
 */
public record KindValidationContext(String tenantId, String projectId, String docPath, DocRefs docs) {}
