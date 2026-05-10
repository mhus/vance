package de.mhus.vance.brain.ai.attachment;

/**
 * Materialised attachment ready to feed into a provider call. Built
 * by {@link AttachmentResolver} from an
 * {@link de.mhus.vance.api.attachment.AttachmentRef} after
 * scope-/size-/MIME-validation.
 *
 * @param documentId      original document id (for logs/tracing)
 * @param mimeType        normalised mime type, lower-case
 *                        ({@code "image/png"}, {@code "application/pdf"}, …)
 * @param data            the raw file bytes
 * @param originalFilename short display name extracted from the
 *                        document path; surfaced to the LLM in the
 *                        text-extract fallback so the model can refer
 *                        to it ({@code "see attachment foo.pdf"})
 */
public record ResolvedAttachment(
        String documentId,
        String mimeType,
        byte[] data,
        String originalFilename) {

    public ResolvedAttachment {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId is blank");
        }
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType is blank");
        }
        if (data == null) {
            throw new IllegalArgumentException("data is null");
        }
        if (originalFilename == null) {
            throw new IllegalArgumentException("originalFilename is null");
        }
    }

    public boolean isImage() {
        return mimeType.startsWith("image/");
    }

    public boolean isPdf() {
        return "application/pdf".equals(mimeType);
    }

    /** Plain text-ish content that can ride as a {@code TextContent} block. */
    public boolean isText() {
        return mimeType.startsWith("text/")
                || "application/json".equals(mimeType)
                || "application/yaml".equals(mimeType)
                || "application/xml".equals(mimeType);
    }
}
