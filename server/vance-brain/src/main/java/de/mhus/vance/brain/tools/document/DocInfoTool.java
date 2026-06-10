package de.mhus.vance.brain.tools.document;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Cheap metadata lookup for a single document, identified by
 * {@code path} (within the active project) or {@code id}. Returns
 * everything that's gratis from the {@link DocumentDocument} record
 * — identity, mime/kind/size, tags, front-matter headers, lifecycle,
 * and cached summary — without loading the content body.
 *
 * <p>Intended as the cheap counterpart to {@code doc_read}: use this
 * to decide <em>whether</em> the full body is worth pulling, e.g.
 * "is this still 200 bytes of stub markdown, or did it grow into a
 * 40k chapter?". Read-only and used by every engine that already
 * uses {@code doc_read} / {@code doc_summary}.
 *
 * <p>Optional {@code stats=true} param fills {@code charCount},
 * {@code wordCount}, {@code lineCount}. For inline-text documents
 * those are computed from the already-loaded {@link
 * DocumentDocument#getInlineText() inlineText}; for storage-backed
 * documents the body is pulled through {@link
 * DocumentService#loadContent} (so {@code stats=true} is no longer
 * truly cheap there — the param defaults to {@code false}).
 */
@Component
@RequiredArgsConstructor
public class DocInfoTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of(
                            "type", "string",
                            "description", "Optional project name. Defaults "
                                    + "to the active project. Ignored when "
                                    + "'id' is provided."),
                    "path", Map.of(
                            "type", "string",
                            "description", "Document path inside the project, "
                                    + "e.g. 'notes/thesis/ch1.md'."),
                    "id", Map.of(
                            "type", "string",
                            "description", "Alternative: Mongo id of the "
                                    + "document. Use one of path/id."),
                    "stats", Map.of(
                            "type", "boolean",
                            "description", "Default false. When true, "
                                    + "additionally returns charCount, "
                                    + "wordCount, lineCount — gratis for "
                                    + "inline text, requires a full content "
                                    + "load for storage-backed documents.")),
            "required", List.of());

    private final EddieContext eddieContext;
    private final DocumentService documentService;

    @Override
    public String name() {
        return "doc_info";
    }

    @Override
    public String description() {
        return "Inspect a document's metadata without loading its body. "
                + "Identify by path (within the active project) or by id. "
                + "Returns identity (id, path, name, title), shape "
                + "(mimeType, kind, size in bytes, tags, front-matter "
                + "headers, inline flag), lifecycle (status, createdAt, "
                + "createdBy, version, lastArchivedAt), and cached AI "
                + "metadata (summary if cached, summarizedAt, autoSummary, "
                + "ragEnabled). Pass stats=true to additionally compute "
                + "charCount/wordCount/lineCount (loads the body for "
                + "storage-backed docs).";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("read-only", "document");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String id = paramString(params, "id");
        String path = paramString(params, "path");
        if (id == null && path == null) {
            throw new ToolException("Provide either 'path' or 'id'");
        }
        boolean withStats = paramBool(params, "stats", false);

        DocumentDocument doc;
        if (id != null) {
            doc = documentService.findById(id)
                    .orElseThrow(() -> new ToolException(
                            "Document with id '" + id + "' not found"));
            if (!ctx.tenantId().equals(doc.getTenantId())) {
                throw new ToolException("Document with id '" + id
                        + "' is not in your tenant");
            }
        } else {
            ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
            doc = documentService.findByPath(ctx.tenantId(), project.getName(), path)
                    .orElseThrow(() -> new ToolException(
                            "Document '" + path + "' not found in project '"
                                    + project.getName() + "'"));
        }

        Map<String, Object> out = new LinkedHashMap<>();

        // Identity
        out.put("id", doc.getId());
        out.put("projectId", doc.getProjectId());
        out.put("path", doc.getPath());
        out.put("name", doc.getName());
        if (doc.getTitle() != null) out.put("title", doc.getTitle());
        if (doc.getLineageId() != null && !doc.getLineageId().isBlank()) {
            out.put("lineageId", doc.getLineageId());
        }

        // Shape
        if (doc.getMimeType() != null) out.put("mimeType", doc.getMimeType());
        if (doc.getKind() != null) out.put("kind", doc.getKind());
        out.put("size", doc.getSize());
        out.put("inline", documentService.readContent(doc) != null);
        if (doc.getTags() != null && !doc.getTags().isEmpty()) {
            out.put("tags", doc.getTags());
        }
        if (doc.getHeaders() != null && !doc.getHeaders().isEmpty()) {
            out.put("headers", doc.getHeaders());
        }

        // Lifecycle
        if (doc.getStatus() != null) out.put("status", doc.getStatus().name());
        if (doc.getCreatedAt() != null) out.put("createdAt", doc.getCreatedAt().toString());
        if (doc.getCreatedBy() != null) out.put("createdBy", doc.getCreatedBy());
        if (doc.getVersion() != null) out.put("version", doc.getVersion());
        if (doc.getLastArchivedAt() != null) {
            out.put("lastArchivedAt", doc.getLastArchivedAt().toString());
        }

        // Cached AI metadata (no lazy-gen — that's what doc_summary is for).
        if (doc.getSummary() != null && !doc.getSummary().isBlank()) {
            out.put("summary", doc.getSummary());
        }
        if (doc.getSummarizedAt() != null) {
            out.put("summarizedAt", doc.getSummarizedAt().toString());
        }
        out.put("autoSummary", doc.isAutoSummary());
        if (doc.getRagEnabled() != null) out.put("ragEnabled", doc.getRagEnabled());

        if (withStats) {
            String content = loadAsText(doc);
            out.put("charCount", content.length());
            out.put("wordCount", countWords(content));
            out.put("lineCount", countLines(content));
        }

        return out;
    }

    private String loadAsText(DocumentDocument doc) {
        if (documentService.readContent(doc) != null) {
            return documentService.readContent(doc);
        }
        try (InputStream in = documentService.loadContent(doc)) {
            byte[] bytes = in.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException(
                    "Failed to read document content for stats: " + e.getMessage(), e);
        }
    }

    private static int countWords(String text) {
        if (text.isEmpty()) return 0;
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                inWord = false;
            } else if (!inWord) {
                inWord = true;
                count++;
            }
        }
        return count;
    }

    private static int countLines(String text) {
        if (text.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        // Trailing newline → the chunk after it is an empty line; drop it.
        if (text.charAt(text.length() - 1) == '\n') count--;
        return count;
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static boolean paramBool(
            @Nullable Map<String, Object> params, String key, boolean fallback) {
        if (params == null) return fallback;
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return fallback;
    }
}
