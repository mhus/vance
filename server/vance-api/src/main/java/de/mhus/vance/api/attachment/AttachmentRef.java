package de.mhus.vance.api.attachment;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Reference to a project-scoped {@code DocumentDocument} that should
 * accompany an LLM call as a multimodal content block (image, PDF, …).
 *
 * <p>Only the document id rides on the wire. The tenant + project
 * scope come from the authenticated session and are enforced by
 * {@code AttachmentResolver} on the server — clients cannot point
 * at documents in other projects by guessing ids.
 *
 * <p>Resolution to bytes + mimeType happens lazily, immediately
 * before the LLM call: the resolver streams from
 * {@code DocumentService.loadContent(...)}, applies the configured
 * size limit, and either hands the binary content to the chosen
 * provider as an {@code ImageContent} / {@code PdfFileContent}
 * block, or — for providers / models without native PDF support —
 * extracts text via PDFBox and prepends it as a {@code TextContent}
 * block.
 */
@GenerateTypeScript("attachment")
public record AttachmentRef(String documentId) {

    public AttachmentRef {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId is blank");
        }
    }
}
