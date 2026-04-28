package de.mhus.vance.brain.tools.vance;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Creates a text document in the active project. Use this when Vance
 * has the content already in hand (e.g. she just composed a summary
 * or the user dictated something to remember).
 *
 * <p>For URL-sourced imports use {@code doc_import_url}.
 */
@Component
@RequiredArgsConstructor
public class DocCreateTextTool implements Tool {

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
                                    + "e.g. 'imports/apollo-13.md'. Must be "
                                    + "unique per project."),
                    "title", Map.of(
                            "type", "string",
                            "description", "Optional human title."),
                    "tags", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "Optional tag list."),
                    "content", Map.of(
                            "type", "string",
                            "description", "Document body (markdown / plain text).")),
            "required", List.of("path", "content"));

    private final VanceContext vanceContext;
    private final DocumentService documentService;

    @Override
    public String name() {
        return "doc_create_text";
    }

    @Override
    public String description() {
        return "Create a new text document in the active project. "
                + "Pass path, content, optional title and tags. "
                + "Path must be unique per project.";
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
        String path = paramString(params, "path");
        String content = paramString(params, "content");
        if (path == null) throw new ToolException("'path' is required");
        if (content == null) throw new ToolException("'content' is required");
        String title = paramString(params, "title");
        List<String> tags = paramStringList(params, "tags");

        ProjectDocument project = vanceContext.resolveProject(params, ctx, false);

        DocumentDocument created;
        try {
            created = documentService.createText(
                    ctx.tenantId(),
                    project.getName(),
                    path,
                    title,
                    tags,
                    content,
                    /*createdBy*/ ctx.userId());
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ToolException(e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.getId());
        out.put("projectId", created.getProjectId());
        out.put("path", created.getPath());
        out.put("name", created.getName());
        if (created.getTitle() != null) out.put("title", created.getTitle());
        out.put("size", created.getSize());
        if (created.getTags() != null && !created.getTags().isEmpty()) {
            out.put("tags", created.getTags());
        }
        return out;
    }

    private static @org.jspecify.annotations.Nullable String paramString(
            Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    @SuppressWarnings("unchecked")
    private static @org.jspecify.annotations.Nullable List<String> paramStringList(
            Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (!(v instanceof List<?> raw)) return null;
        List<String> out = new ArrayList<>(raw.size());
        for (Object e : raw) {
            if (e instanceof String s && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out.isEmpty() ? null : out;
    }
}
