package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
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
 * Creates a text document in the active project. Use this when Eddie
 * has the content already in hand (e.g. just composed a summary
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
                            "description", "Optional document path inside the project, "
                                    + "e.g. 'documents/imports/apollo-13.md'. Must be "
                                    + "unique per project. Omitted → auto-generated "
                                    + "under 'documents/' using a slug of the title "
                                    + "(or a short UUID if no title)."),
                    "title", Map.of(
                            "type", "string",
                            "description", "Optional human title. Also seeds the "
                                    + "auto-generated path when 'path' is omitted."),
                    "tags", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "Optional tag list."),
                    "content", Map.of(
                            "type", "string",
                            "description", "Document body (markdown / plain text).")),
            "required", List.of("content"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;

    @Override
    public String name() {
        return "doc_create_text";
    }

    @Override
    public String description() {
        return "Create a long-lived, indexed text DOCUMENT in the "
                + "active project's knowledge base. Use this for "
                + "anything the user might want to find, read or "
                + "reference later — research results, summaries, "
                + "comparison tables, notes, decisions, specs. "
                + "Documents are searchable, can be tagged, get "
                + "auto-summarised, and survive sessions. "
                + "NOT for: short-lived scripts or scratch data you "
                + "want to process with python/bash (use "
                + "workspace_write), or files on the user's own "
                + "machine (use client_file_write). "
                + "Path is optional — omit it to auto-place under "
                + "'documents/' so the default search finds it back. "
                + "Pass content, optional title and tags.";
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
    public java.util.Set<String> labels() {
        return java.util.Set.of("eddie", "write");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = paramString(params, "path");
        String content = paramString(params, "content");
        if (content == null) throw new ToolException("'content' is required");
        String title = paramString(params, "title");
        List<String> tags = paramStringList(params, "tags");

        if (path == null) {
            path = autoPath(title);
        }

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);

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

    /**
     * Auto-place a new doc under {@link DocumentService#DOCUMENTS_FOLDER_PREFIX}
     * when the caller didn't supply a path. Uses a slug of the title
     * when available, otherwise a short UUID — collision is the
     * caller's problem in either case ({@code createText} throws on
     * dup, and the LLM can react by passing a more specific path).
     */
    static String autoPath(@org.jspecify.annotations.Nullable String title) {
        String slug = slugify(title);
        String filename = slug.isEmpty()
                ? java.util.UUID.randomUUID().toString().substring(0, 8)
                : slug;
        return DocumentService.DOCUMENTS_FOLDER_PREFIX + filename + ".md";
    }

    private static String slugify(@org.jspecify.annotations.Nullable String title) {
        if (title == null) return "";
        String slug = title.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.length() > 50 ? slug.substring(0, 50) : slug;
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
