/**
 * Attachment wire types — references to project-scoped
 * {@code DocumentDocument}s that ride along with chat messages
 * (images for vision models, PDFs for document-aware models).
 *
 * <p>Wire payload is intentionally minimal — only the document id.
 * tenantId / projectId are derived server-side from the
 * authenticated session, never trusted from the client. The
 * server-side {@code AttachmentResolver} validates that the document
 * lives in the caller's project before any byte hits an LLM.
 */
@NullMarked
package de.mhus.vance.api.attachment;

import org.jspecify.annotations.NullMarked;
