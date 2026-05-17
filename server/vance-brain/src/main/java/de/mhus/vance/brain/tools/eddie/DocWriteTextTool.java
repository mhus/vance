package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Upsert sibling of {@link DocCreateTextTool}. Same parameters,
 * but writes the document whether or not a doc at {@code path}
 * already exists — overwrites in place when present, creates when
 * not. Use this whenever the same logical artifact can legitimately
 * be re-emitted across retries / recovery loops (Slart's persist
 * phases, idempotent worker reruns).
 *
 * <p>Distinct from {@code doc_create_text} so the LLM has a clear
 * binary choice — create-only vs. overwrite-allowed — instead of
 * a single ambiguous tool whose behaviour depends on prior state.
 */
@Component
@RequiredArgsConstructor
public class DocWriteTextTool implements Tool {

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
                                    + "e.g. 'essay/final-essay.md'. If a doc at this "
                                    + "path already exists it gets overwritten; "
                                    + "otherwise a new one is created. Omitted → "
                                    + "auto-generated under 'documents/' using a slug "
                                    + "of the title (or a short UUID if no title)."),
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
        return "doc_write_text";
    }

    @Override
    public String description() {
        return "Write a text DOCUMENT at a known path in the active "
                + "project's knowledge base, creating it if absent or "
                + "OVERWRITING it in place when one already exists. Use "
                + "this when you have the final content in hand and a "
                + "fixed target path that the same workflow may revisit "
                + "(e.g. a persist phase that re-emits an artifact "
                + "after a recovery loop). For first-time-only writes "
                + "where a duplicate path indicates a logic error, use "
                + "doc_create_text instead. Pass content and the "
                + "exact path you want the file at — no auto-prefixing.";
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
            path = DocCreateTextTool.autoPath(title);
        }

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);

        DocumentDocument written = documentService.upsertText(
                ctx.tenantId(),
                project.getName(),
                path,
                title,
                tags,
                content,
                /*createdBy*/ ctx.userId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", written.getId());
        out.put("projectId", written.getProjectId());
        out.put("path", written.getPath());
        out.put("name", written.getName());
        if (written.getTitle() != null) out.put("title", written.getTitle());
        out.put("size", written.getSize());
        if (written.getTags() != null && !written.getTags().isEmpty()) {
            out.put("tags", written.getTags());
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
        List<String> out = new java.util.ArrayList<>(raw.size());
        for (Object e : raw) {
            if (e instanceof String s && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out.isEmpty() ? null : out;
    }
}
