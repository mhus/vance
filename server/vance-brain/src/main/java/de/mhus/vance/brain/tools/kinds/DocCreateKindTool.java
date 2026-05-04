package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Create a new document with a typed {@code kind} stamp and a
 * starter body matching that kind. Mirrors the {@code buildKindStub}
 * stubs the Web-UI uses for new documents.
 */
@Component
@RequiredArgsConstructor
public class DocCreateKindTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("path", "kind"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.remove("id");
        p.put("kind", Map.of("type", "string",
                "description", "One of: list, tree, mindmap, records, sheet, graph, data, text, schema."));
        p.put("mimeType", Map.of("type", "string",
                "description", "Mime type for the new body. Defaults to a kind-appropriate value: "
                        + "text/markdown for list/tree/mindmap/records, application/json for sheet/graph/data."));
        p.put("title", Map.of("type", "string", "description", "Optional display title."));
        p.put("tags", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "Optional tag list."));
        p.put("body", Map.of("type", "string",
                "description", "Optional initial body. When omitted, a kind-appropriate stub is used."));
        return p;
    }

    private final KindToolSupport support;

    @Override public String name() { return "doc_create_kind"; }
    @Override public String description() {
        return "Create a new document with the given `kind` and an appropriate stub body. "
                + "Returns the new document id. Pass `body` to override the default stub.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("doc-management", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = KindToolSupport.requireString(params, "path");
        String kind = KindToolSupport.requireString(params, "kind").toLowerCase();
        String mimeType = KindToolSupport.paramString(params, "mimeType");
        if (mimeType == null) mimeType = defaultMimeFor(kind);
        String title = KindToolSupport.paramString(params, "title");
        @SuppressWarnings("unchecked")
        List<String> tags = params.get("tags") instanceof List<?> l
                ? l.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : null;
        String body = KindToolSupport.paramRawString(params, "body");
        if (body == null) body = stubFor(kind, mimeType);

        ProjectDocument project = support.eddieContext().resolveProject(params, ctx, false);
        DocumentDocument created;
        try {
            created = support.documentService().create(
                    ctx.tenantId(),
                    project.getName(),
                    path,
                    title,
                    tags,
                    mimeType,
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                    ctx.userId());
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ToolException(e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.getId());
        out.put("projectId", created.getProjectId());
        out.put("path", created.getPath());
        out.put("kind", created.getKind());
        if (created.getMimeType() != null) out.put("mimeType", created.getMimeType());
        return out;
    }

    private static String defaultMimeFor(String kind) {
        return switch (kind) {
            case "list", "tree", "mindmap", "records", "text" -> "text/markdown";
            case "sheet", "graph", "data" -> "application/json";
            case "schema" -> "application/json";
            default -> "text/markdown";
        };
    }

    private static String stubFor(String kind, String mimeType) {
        boolean md = "text/markdown".equals(mimeType) || "text/x-markdown".equals(mimeType);
        boolean json = "application/json".equals(mimeType);
        boolean yaml = mimeType != null && (mimeType.contains("yaml"));
        return switch (kind) {
            case "list" -> md ? "---\nkind: list\n---\n- item 1\n- item 2\n"
                    : json ? "{\n  \"$meta\": { \"kind\": \"list\" },\n  \"items\": []\n}\n"
                    : yaml ? "kind: list\n---\nitems: []\n"
                    : "";
            case "tree" -> md ? "---\nkind: tree\n---\n- parent\n  - child\n"
                    : json ? "{\n  \"$meta\": { \"kind\": \"tree\" },\n  \"items\": []\n}\n"
                    : yaml ? "kind: tree\n---\nitems: []\n"
                    : "";
            case "mindmap" -> md ? "---\nkind: mindmap\n---\n- root\n  - branch\n"
                    : json ? "{\n  \"$meta\": { \"kind\": \"mindmap\" },\n  \"items\": []\n}\n"
                    : yaml ? "kind: mindmap\n---\nitems: []\n"
                    : "";
            case "records" -> md ? "---\nkind: records\nschema: name, value\n---\n"
                    : json ? "{\n  \"$meta\": { \"kind\": \"records\" },\n  \"schema\": [\"name\", \"value\"],\n  \"items\": []\n}\n"
                    : yaml ? "kind: records\n---\nschema: [name, value]\nitems: []\n"
                    : "";
            case "sheet" -> json
                    ? "{\n  \"$meta\": { \"kind\": \"sheet\" },\n  \"schema\": [\"A\", \"B\", \"C\"],\n  \"rows\": 5,\n  \"cells\": []\n}\n"
                    : "kind: sheet\n---\nschema: [A, B, C]\nrows: 5\ncells: []\n";
            case "graph" -> json
                    ? "{\n  \"$meta\": { \"kind\": \"graph\" },\n  \"graph\": { \"directed\": true },\n  \"nodes\": [],\n  \"edges\": []\n}\n"
                    : "kind: graph\n---\ngraph:\n  directed: true\nnodes: []\nedges: []\n";
            case "data" -> json
                    ? "{\n  \"$meta\": { \"kind\": \"data\" }\n}\n"
                    : "kind: data\n---\n{}\n";
            default -> md ? "---\nkind: " + kind + "\n---\n"
                    : json ? "{\n  \"$meta\": { \"kind\": \"" + kind + "\" }\n}\n"
                    : "kind: " + kind + "\n---\n";
        };
    }
}
