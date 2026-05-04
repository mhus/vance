package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.documents.DocumentBufferService;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shared helpers for the kind-aware document tools — document
 * lookup (by {@code id} or {@code path}), kind validation, param
 * coercion. All tool calls funnel reads/writes through
 * {@link DocumentBufferService} so the per-process write-behind
 * cache stays in effect.
 */
@Component
public class KindToolSupport {

    private final DocumentBufferService bufferService;
    private final DocumentService documentService;
    private final EddieContext eddieContext;

    public KindToolSupport(
            DocumentBufferService bufferService,
            DocumentService documentService,
            EddieContext eddieContext) {
        this.bufferService = bufferService;
        this.documentService = documentService;
        this.eddieContext = eddieContext;
    }

    public DocumentBufferService buffer() {
        return bufferService;
    }

    public DocumentService documentService() {
        return documentService;
    }

    public EddieContext eddieContext() {
        return eddieContext;
    }

    /**
     * Look up a document by {@code id} or by ({@code projectId},
     * {@code path}) — same conventions as {@code doc_read}. Reads
     * through the buffer so in-flight mutations from earlier tool
     * calls in this process are visible.
     */
    public DocumentDocument loadDocument(Map<String, Object> params, ToolInvocationContext ctx) {
        String id = paramString(params, "id");
        String path = paramString(params, "path");
        if (id == null && path == null) {
            throw new ToolException("Provide either 'path' or 'id'");
        }
        if (id != null) {
            DocumentDocument doc = bufferService.read(ctx.processId(), id);
            if (doc == null) throw new ToolException("Document with id '" + id + "' not found");
            if (!ctx.tenantId().equals(doc.getTenantId())) {
                throw new ToolException("Document with id '" + id + "' is not in your tenant");
            }
            return doc;
        }
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        DocumentDocument disk = documentService.findByPath(ctx.tenantId(), project.getName(), path)
                .orElseThrow(() -> new ToolException(
                        "Document '" + path + "' not found in project '" + project.getName() + "'"));
        // Re-route through the buffer so subsequent reads in this
        // process see our writes.
        DocumentDocument buffered = bufferService.read(ctx.processId(), disk.getId());
        return buffered != null ? buffered : disk;
    }

    /** Validate that the document's {@code kind} is in the expected
     *  set; throws otherwise. */
    public DocumentDocument requireKind(DocumentDocument doc, String... expected) {
        return requireKind(doc, Set.of(expected));
    }

    public DocumentDocument requireKind(DocumentDocument doc, Set<String> expected) {
        String kind = doc.getKind();
        if (kind == null || !expected.contains(kind.toLowerCase())) {
            throw new ToolException("Document " + identify(doc)
                    + " has kind '" + kind + "', expected one of " + expected);
        }
        return doc;
    }

    /** Validate that the inline body is editable (not storage-backed). */
    public DocumentDocument requireInline(DocumentDocument doc) {
        if (doc.getInlineText() == null) {
            throw new ToolException("Document " + identify(doc)
                    + " is storage-backed; only inline documents are editable.");
        }
        return doc;
    }

    public String identify(DocumentDocument doc) {
        return "id=" + doc.getId() + " path='" + doc.getPath() + "'";
    }

    /**
     * Write a fresh body into the buffer for this process and
     * document. Subsequent reads see the new body; the actual Mongo
     * write is deferred until the buffer's flush deadline or the
     * process closes.
     */
    public void writeBody(DocumentDocument doc, String newBody, ToolInvocationContext ctx) {
        bufferService.writeBody(ctx.processId(), doc.getId(), newBody);
    }

    // ── Parameter helpers ─────────────────────────────────────────

    public static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    public static String requireString(@Nullable Map<String, Object> params, String key) {
        String v = paramString(params, key);
        if (v == null) throw new ToolException("Missing required parameter '" + key + "'");
        return v;
    }

    public static @Nullable String paramRawString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s ? s : null;
    }

    public static String requireRawString(@Nullable Map<String, Object> params, String key) {
        String v = paramRawString(params, key);
        if (v == null) throw new ToolException("Missing required parameter '" + key + "'");
        return v;
    }

    public static @Nullable Integer paramInt(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    public static int requireInt(@Nullable Map<String, Object> params, String key) {
        Integer v = paramInt(params, key);
        if (v == null) throw new ToolException("Missing or non-numeric parameter '" + key + "'");
        return v;
    }

    public static @Nullable Boolean paramBoolean(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            String t = s.trim().toLowerCase();
            if (t.equals("true")) return true;
            if (t.equals("false")) return false;
        }
        return null;
    }

    public static @SuppressWarnings("unchecked") @Nullable Map<String, Object> paramMap(
            @Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    public static @SuppressWarnings("unchecked") @Nullable List<Object> paramList(
            @Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof List<?> l ? (List<Object>) l : null;
    }

    /** Common JSON-schema fragment for the {@code id}/{@code path}/
     *  {@code projectId} document selector. Tools merge it into
     *  their own {@code paramsSchema}. */
    public static Map<String, Object> documentSelectorProperties() {
        return Map.of(
                "projectId", Map.of(
                        "type", "string",
                        "description", "Optional project name. Defaults to the active project."),
                "path", Map.of(
                        "type", "string",
                        "description", "Document path inside the project."),
                "id", Map.of(
                        "type", "string",
                        "description", "Alternative: Mongo id of the document. Use one of path/id."));
    }
}
