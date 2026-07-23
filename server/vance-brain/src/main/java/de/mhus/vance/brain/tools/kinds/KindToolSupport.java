package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.documents.DocumentBufferService;
import de.mhus.vance.brain.documents.DocumentInvalidationEmitter;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
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
    private final DocumentInvalidationEmitter invalidationEmitter;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    public KindToolSupport(
            DocumentBufferService bufferService,
            DocumentService documentService,
            EddieContext eddieContext,
            DocumentInvalidationEmitter invalidationEmitter,
            de.mhus.vance.shared.permission.PermissionService permissionService,
            de.mhus.vance.brain.permission.SecurityContextFactory contextFactory) {
        this.bufferService = bufferService;
        this.documentService = documentService;
        this.eddieContext = eddieContext;
        this.invalidationEmitter = invalidationEmitter;
        this.permissionService = permissionService;
        this.contextFactory = contextFactory;
    }

    /**
     * Per-document authorization gate for the LLM-tool write path
     * (permission-system F1, finding #9). ToolDispatcher only enforces a
     * coarse EXECUTE on the *caller's* scope; a tool can target another
     * project or a reserved {@code _vance/...} path, so the actual target
     * document is authorized here. The subject is derived from the tool
     * context ({@code forToolSubject} maps a null userId → SYSTEM, so
     * headless/scheduler-triggered writes pass; a real user is checked).
     * The resolver decides — reserved-prefix writes need ADMIN (R4),
     * ordinary project docs need WRITER (R3). Rolled out under the
     * {@code vance.permission.shadow} switch.
     */
    public void enforceDocWrite(ToolInvocationContext ctx, String projectName, String path,
            de.mhus.vance.shared.permission.Action action) {
        permissionService.enforce(
                contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()),
                new de.mhus.vance.shared.permission.Resource.Document(
                        ctx.tenantId(), projectName, path),
                action);
    }

    /** Overload for an already-loaded document. */
    public void enforceDocWrite(ToolInvocationContext ctx, DocumentDocument doc,
            de.mhus.vance.shared.permission.Action action) {
        enforceDocWrite(ctx, doc.getProjectId(), doc.getPath(), action);
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

    /**
     * Read the document body as UTF-8 — buffer-aware. Tools that have a
     * {@link ToolInvocationContext} call the 2-arg overload so in-flight
     * buffered edits from prior tool calls in the same process are
     * visible. The 1-arg form bypasses the buffer (only safe when the
     * caller knows no buffer can apply, e.g. one-shot reads in startup
     * code).
     */
    public String readBody(DocumentDocument doc) {
        return documentService.readContent(doc);
    }

    public String readBody(DocumentDocument doc, ToolInvocationContext ctx) {
        if (ctx != null && ctx.processId() != null) {
            String buffered = bufferService.peekBody(ctx.processId(), doc.getId());
            if (buffered != null) return buffered;
        }
        return documentService.readContent(doc);
    }

    /**
     * Legacy guard from the inline-storage era — kept as an identity
     * no-op so the wide existing call surface compiles unchanged. Every
     * document is storage-backed now, so editability is universal.
     *
     * @deprecated drop the call when touching the surrounding code; the
     *     return value is the same {@code doc} you passed in.
     */
    @Deprecated
    public DocumentDocument requireInline(DocumentDocument doc) {
        return doc;
    }

    public String identify(DocumentDocument doc) {
        return "id=" + doc.getId() + " path='" + doc.getPath() + "'";
    }

    /**
     * Write a fresh body into the buffer for this process and
     * document. Force-flushes the buffer immediately afterwards so
     * downstream readers (notably the Cortex tab that re-fetches on
     * {@link de.mhus.vance.api.ws.MessageType#DOCUMENT_INVALIDATE})
     * see the new content and not the stale on-disk version.
     *
     * <p>Trade-off: each tool-side body-mutation triggers one Mongo
     * write rather than the buffer's idle-flush coalescing. Acceptable
     * for the LLM tool cadence (≤ 1 mutation/second per process); the
     * buffer keeps its read-after-write consistency role for tool
     * callers within the same process turn.
     *
     * <p>Always emits a body invalidation frame to the originating
     * session — the Cortex tab decides whether to react based on its
     * own openDocumentIds.
     */
    public void writeBody(DocumentDocument doc, String newBody, ToolInvocationContext ctx) {
        enforceDocWrite(ctx, doc, de.mhus.vance.shared.permission.Action.WRITE);
        bufferService.writeBody(ctx.processId(), doc.getId(), newBody);
        bufferService.flush(ctx.processId(), doc.getId());
        invalidationEmitter.emitBody(ctx.sessionId(), doc);
    }

    /**
     * Emit a {@code notes}-kind invalidation for the given document on
     * the session-WS. Called from {@code doc_note_*} tools after a
     * successful {@link DocumentService#addNote}/{@code updateNote}/
     * {@code deleteNote} — those bypass the body-buffer and persist
     * atomically, so no flush is needed here.
     */
    public void emitNotesInvalidate(DocumentDocument doc, ToolInvocationContext ctx) {
        invalidationEmitter.emitNotes(ctx.sessionId(), doc);
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
