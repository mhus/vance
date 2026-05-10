package de.mhus.vance.brain.ai.attachment;

import de.mhus.vance.api.attachment.AttachmentRef;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Resolves {@link AttachmentRef}s to {@link ResolvedAttachment}s for
 * an LLM call. Concrete responsibilities:
 *
 * <ol>
 *   <li>Document lookup via {@link DocumentService#findById(String)}.</li>
 *   <li>Scope check: the document's
 *       {@code (tenantId, projectId)} must match the caller's expected
 *       scope. Mismatches throw {@link AttachmentException} so a
 *       cross-project leak is impossible by accident.</li>
 *   <li>MIME-type allowlist (image/*, application/pdf, plain-textish
 *       formats). Unsupported MIMEs throw rather than getting silently
 *       dropped — the user should know the attachment didn't make it.</li>
 *   <li>Soft size limits: per-file
 *       ({@code vance.ai.attachment.max-bytes-per-file}, default 20 MB)
 *       and per-request ({@code vance.ai.attachment.max-bytes-per-request},
 *       default 32 MB). Provider-level hard limits enforce the rest;
 *       the soft limits exist to fail fast before we OOM the brain.</li>
 *   <li>Streamed read of {@link DocumentService#loadContent(DocumentDocument)}
 *       into a bounded byte buffer.</li>
 * </ol>
 */
@Service
@Slf4j
public class AttachmentResolver {

    /**
     * MIME types we accept. Anything else fails fast — better to surface
     * the gap than send a binary blob the LLM would reject silently.
     */
    private static final Set<String> ALLOWED_MIMES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp",
            "application/pdf",
            "text/plain", "text/markdown", "text/html", "text/csv",
            "application/json", "application/yaml", "application/xml");

    private final DocumentService documentService;
    private final long maxBytesPerFile;
    private final long maxBytesPerRequest;

    public AttachmentResolver(
            DocumentService documentService,
            @Value("${vance.ai.attachment.max-bytes-per-file:20971520}") long maxBytesPerFile,
            @Value("${vance.ai.attachment.max-bytes-per-request:33554432}") long maxBytesPerRequest) {
        this.documentService = documentService;
        this.maxBytesPerFile = maxBytesPerFile;
        this.maxBytesPerRequest = maxBytesPerRequest;
        log.info("AttachmentResolver: maxPerFile={} bytes, maxPerRequest={} bytes",
                maxBytesPerFile, maxBytesPerRequest);
    }

    /**
     * Resolve a list of refs against a known scope. {@code refs} may be
     * empty / null — both yield an empty result.
     *
     * @throws AttachmentException on missing docs, scope mismatch,
     *         disallowed MIME, file-size or request-size overrun, or
     *         I/O errors reading from storage
     */
    public List<ResolvedAttachment> resolveAll(
            List<AttachmentRef> refs, String expectedTenantId, String expectedProjectId) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<ResolvedAttachment> out = new ArrayList<>(refs.size());
        long total = 0;
        for (AttachmentRef ref : refs) {
            ResolvedAttachment resolved = resolveOne(ref, expectedTenantId, expectedProjectId);
            total += resolved.data().length;
            if (total > maxBytesPerRequest) {
                throw new AttachmentException(
                        "Attachment payload exceeds per-request limit: " + total
                                + " > " + maxBytesPerRequest + " bytes");
            }
            out.add(resolved);
        }
        return out;
    }

    private ResolvedAttachment resolveOne(
            AttachmentRef ref, String expectedTenantId, String expectedProjectId) {
        DocumentDocument doc = documentService.findById(ref.documentId())
                .orElseThrow(() -> new AttachmentException(
                        "Attachment document not found: " + ref.documentId()));

        if (!equalsScope(doc.getTenantId(), expectedTenantId)
                || !equalsScope(doc.getProjectId(), expectedProjectId)) {
            // Don't leak the actual scope — log it server-side, return a
            // neutral message to the caller.
            log.warn("Attachment scope mismatch: docId='{}' doc-scope='{}/{}' caller-scope='{}/{}'",
                    ref.documentId(), doc.getTenantId(), doc.getProjectId(),
                    expectedTenantId, expectedProjectId);
            throw new AttachmentException(
                    "Attachment is not accessible in this scope: " + ref.documentId());
        }

        String mimeType = normaliseMime(doc.getMimeType());
        if (!ALLOWED_MIMES.contains(mimeType)) {
            throw new AttachmentException(
                    "Attachment MIME type not allowed: '" + mimeType
                            + "' (docId=" + ref.documentId() + ")");
        }

        if (doc.getSize() > maxBytesPerFile) {
            throw new AttachmentException(
                    "Attachment exceeds per-file limit: " + doc.getSize()
                            + " > " + maxBytesPerFile + " bytes (docId="
                            + ref.documentId() + ")");
        }

        byte[] data = readBoundedBytes(doc);
        return new ResolvedAttachment(
                ref.documentId(),
                mimeType,
                data,
                deriveFilename(doc));
    }

    /**
     * Reads the document content via the streamed
     * {@link DocumentService#loadContent} API, capping at
     * {@link #maxBytesPerFile} + 1 byte so we can detect under-reported
     * sizes without loading the whole stream.
     */
    private byte[] readBoundedBytes(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            byte[] data = in.readNBytes((int) Math.min(maxBytesPerFile + 1, Integer.MAX_VALUE));
            if (data.length > maxBytesPerFile) {
                throw new AttachmentException(
                        "Attachment content stream exceeds per-file limit (docId="
                                + doc.getId() + ", reported size=" + doc.getSize() + ")");
            }
            return data;
        } catch (IOException e) {
            throw new AttachmentException(
                    "Failed to read attachment content (docId=" + doc.getId() + "): "
                            + e.getMessage(), e);
        }
    }

    private static String normaliseMime(String mime) {
        if (mime == null || mime.isBlank()) {
            return "application/octet-stream";
        }
        return mime.trim().toLowerCase(Locale.ROOT);
    }

    private static String deriveFilename(DocumentDocument doc) {
        String name = doc.getName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        String path = doc.getPath();
        if (path == null || path.isBlank()) {
            return "attachment";
        }
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? path : path.substring(lastSlash + 1);
    }

    private static boolean equalsScope(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
