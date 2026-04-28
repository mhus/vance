package de.mhus.vance.brain.tools.vance;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads a document by path or id (or both). Truncates the returned
 * content past {@link #MAX_BODY_CHARS} so a giant doc doesn't blow
 * out the LLM context — full size is reported in {@code contentLength}.
 */
@Component
@RequiredArgsConstructor
public class DocReadTool implements Tool {

    private static final int MAX_BODY_CHARS = 50_000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of(
                            "type", "string",
                            "description", "Optional project name. Defaults "
                                    + "to the active project."),
                    "path", Map.of(
                            "type", "string",
                            "description", "Document path inside the project, "
                                    + "e.g. 'notes/thesis/ch1.md'."),
                    "id", Map.of(
                            "type", "string",
                            "description", "Alternative: Mongo id of the "
                                    + "document. Use one of path/id.")),
            "required", List.of());

    private final VanceContext vanceContext;
    private final DocumentService documentService;

    @Override
    public String name() {
        return "doc_read";
    }

    @Override
    public String description() {
        return "Read a document's text content. Identify it by path "
                + "(within the active project) or by id. Returns title, "
                + "tags, mimeType, content (text). Long documents are "
                + "truncated past " + MAX_BODY_CHARS + " characters.";
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String id = paramString(params, "id");
        String path = paramString(params, "path");
        if (id == null && path == null) {
            throw new ToolException("Provide either 'path' or 'id'");
        }

        DocumentDocument doc;
        if (id != null) {
            doc = documentService.findById(id)
                    .orElseThrow(() -> new ToolException(
                            "Document with id '" + id + "' not found"));
            // Sanity: doc must belong to the caller's tenant.
            if (!ctx.tenantId().equals(doc.getTenantId())) {
                throw new ToolException("Document with id '" + id
                        + "' is not in your tenant");
            }
        } else {
            ProjectDocument project = vanceContext.resolveProject(params, ctx, false);
            doc = documentService.findByPath(ctx.tenantId(), project.getName(), path)
                    .orElseThrow(() -> new ToolException(
                            "Document '" + path + "' not found in project '"
                                    + project.getName() + "'"));
        }

        String content = loadAsText(doc);
        int fullLength = content.length();
        boolean truncated = fullLength > MAX_BODY_CHARS;
        String body = truncated ? content.substring(0, MAX_BODY_CHARS) : content;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", doc.getId());
        out.put("projectId", doc.getProjectId());
        out.put("path", doc.getPath());
        out.put("name", doc.getName());
        if (doc.getTitle() != null) out.put("title", doc.getTitle());
        if (doc.getMimeType() != null) out.put("mimeType", doc.getMimeType());
        if (doc.getTags() != null && !doc.getTags().isEmpty()) {
            out.put("tags", doc.getTags());
        }
        out.put("contentLength", fullLength);
        out.put("truncated", truncated);
        out.put("content", body);
        return out;
    }

    private String loadAsText(DocumentDocument doc) {
        if (doc.getInlineText() != null) {
            return doc.getInlineText();
        }
        try (InputStream in = documentService.loadContent(doc)) {
            byte[] bytes = in.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException(
                    "Failed to read document content: " + e.getMessage(), e);
        }
    }

    private static @org.jspecify.annotations.Nullable String paramString(
            Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
