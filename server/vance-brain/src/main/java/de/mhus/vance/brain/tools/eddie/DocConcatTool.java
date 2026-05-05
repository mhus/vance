package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Deterministically concatenate the text content of N source documents
 * into one new target document — verbatim, no LLM in the path. Built
 * for pipelines like the essay-kit's "merge all chapter docs into one
 * final manuscript" step where any reformulation by an aggregator-worker
 * would be a regression.
 *
 * <p>Sources are read in declared order. Optional {@code separator}
 * glues them together (default: blank line); optional {@code header}
 * and {@code footer} let the caller frame the output without needing
 * a follow-up edit. The target path must be unique within its project
 * (no overwrite) — same contract as {@code doc_create_text}.
 */
@Component
@RequiredArgsConstructor
public class DocConcatTool implements Tool {

    private static final String DEFAULT_SEPARATOR = "\n\n";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of(
                            "type", "string",
                            "description", "Optional project name. Defaults "
                                    + "to the active project. Sources and "
                                    + "target both live in this project."),
                    "sources", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "Ordered list of source document "
                                    + "paths to concatenate."),
                    "target", Map.of(
                            "type", "string",
                            "description", "Target document path. Must not "
                                    + "exist yet."),
                    "separator", Map.of(
                            "type", "string",
                            "description", "String inserted between source "
                                    + "bodies. Default: '\\n\\n'."),
                    "header", Map.of(
                            "type", "string",
                            "description", "Optional text prepended before "
                                    + "the first source body."),
                    "footer", Map.of(
                            "type", "string",
                            "description", "Optional text appended after "
                                    + "the last source body."),
                    "title", Map.of(
                            "type", "string",
                            "description", "Optional title for the target."),
                    "tags", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "Optional tag list for the target.")),
            "required", List.of("sources", "target"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;

    @Override
    public String name() {
        return "doc_concat";
    }

    @Override
    public String description() {
        return "Concatenate the text of several documents (verbatim) into "
                + "one new target document. Pass `sources` (ordered path "
                + "list) and `target` path. Optional `separator`, `header`, "
                + "`footer`, `title`, `tags`. The target path must not "
                + "exist yet — same uniqueness rule as doc_create_text.";
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
        List<String> sources = paramStringList(params, "sources");
        if (sources == null || sources.isEmpty()) {
            throw new ToolException("'sources' must be a non-empty list of paths");
        }
        String target = paramString(params, "target");
        if (target == null) throw new ToolException("'target' is required");
        String separator = params != null && params.get("separator") instanceof String s
                ? s : DEFAULT_SEPARATOR;
        String header = paramString(params, "header");
        String footer = paramString(params, "footer");
        String title = paramString(params, "title");
        List<String> tags = paramStringList(params, "tags");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);

        StringBuilder body = new StringBuilder();
        if (header != null) body.append(header);
        List<Map<String, Object>> sourceMeta = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            String path = sources.get(i);
            DocumentDocument doc = documentService.findByPath(
                            ctx.tenantId(), project.getName(), path)
                    .orElseThrow(() -> new ToolException(
                            "Source document '" + path + "' not found in project '"
                                    + project.getName() + "'"));
            String text = loadAsText(doc);
            if (i > 0 || header != null) body.append(separator);
            body.append(text);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("path", doc.getPath());
            meta.put("contentLength", text.length());
            sourceMeta.add(meta);
        }
        if (footer != null) body.append(separator).append(footer);

        DocumentDocument created;
        try {
            created = documentService.createText(
                    ctx.tenantId(),
                    project.getName(),
                    target,
                    title,
                    tags,
                    body.toString(),
                    /*createdBy*/ ctx.userId());
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ToolException(e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.getId());
        out.put("projectId", created.getProjectId());
        out.put("path", created.getPath());
        out.put("size", created.getSize());
        out.put("sources", sourceMeta);
        return out;
    }

    private String loadAsText(DocumentDocument doc) {
        if (doc.getInlineText() != null) return doc.getInlineText();
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException(
                    "Failed to read source document '" + doc.getPath()
                            + "': " + e.getMessage(), e);
        }
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
