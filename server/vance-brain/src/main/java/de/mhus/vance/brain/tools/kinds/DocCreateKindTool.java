package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
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
                "description", "One of: list, checklist, tree, mindmap, records, sheet, graph, chart, slides, data, text, schema."));
        p.put("mimeType", Map.of("type", "string",
                "description", "Mime type for the new body. Defaults to a kind-appropriate value: "
                        + "text/markdown for list/checklist/tree/mindmap/records/slides, application/json for sheet/graph/chart/data."));
        p.put("title", Map.of("type", "string", "description", "Optional display title."));
        p.put("tags", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "Optional tag list."));
        p.put("body", Map.of("type", "string",
                "description", "Optional initial body. When omitted, a kind-appropriate stub is used."));
        return p;
    }

    private final KindToolSupport support;
    private final DocumentLinkBuilder linkBuilder;

    @Override public String name() { return "doc_create_kind"; }
    @Override public String description() {
        return "Create a new document with the given `kind` and an appropriate stub body. "
                + "Returns the new document id. Pass `body` to override the default stub.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("doc-management", "eddie", "write", "document"); }

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

        // The user explicitly asked for this `kind`. The body's
        // front-matter parsing (via applyHeader) is the usual path,
        // but the LLM occasionally writes a body without the
        // `---kind: <name>---` front-matter — particularly for kinds
        // like `slides` where the `---` separators serve a second
        // purpose. Stamp the kind directly so the editor always
        // recognises the document regardless of body shape.
        if (!kind.equalsIgnoreCase(created.getKind())) {
            support.documentService().setKind(created.getId(), kind);
            created.setKind(kind);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.getId());
        out.put("projectId", created.getProjectId());
        out.put("path", created.getPath());
        out.put("kind", created.getKind());
        if (created.getMimeType() != null) out.put("mimeType", created.getMimeType());
        // Pre-built Markdown link so the LLM can embed the new doc
        // into its reply without a second tool round-trip. See
        // specification/inline-and-embedded-content.md §10.1.
        out.put("markdownLink", linkBuilder.linkFor(created, ctx.projectId()));
        return out;
    }

    private static String defaultMimeFor(String kind) {
        return switch (kind) {
            case "list", "checklist", "tree", "mindmap", "records", "slides", "text" -> "text/markdown";
            case "sheet", "graph", "chart", "data" -> "application/json";
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
                    : yaml ? "$meta:\n  kind: list\nitems: []\n"
                    : "";
            case "checklist" -> md ? "---\nkind: checklist\n---\n- [ ] first task\n- [ ] second task\n"
                    : json ? "{\n  \"$meta\": { \"kind\": \"checklist\" },\n  \"items\": [\n    { \"text\": \"first task\" },\n    { \"text\": \"second task\" }\n  ]\n}\n"
                    : yaml ? "$meta:\n  kind: checklist\nitems:\n  - text: first task\n  - text: second task\n"
                    : "";
            case "tree" -> md ? "---\nkind: tree\n---\n- parent\n  - child\n"
                    : json ? "{\n  \"$meta\": { \"kind\": \"tree\" },\n  \"items\": []\n}\n"
                    : yaml ? "$meta:\n  kind: tree\nitems: []\n"
                    : "";
            case "mindmap" -> md ? "---\nkind: mindmap\n---\n- root\n  - branch\n"
                    : json ? "{\n  \"$meta\": { \"kind\": \"mindmap\" },\n  \"items\": []\n}\n"
                    : yaml ? "$meta:\n  kind: mindmap\nitems: []\n"
                    : "";
            case "records" -> md ? "---\nkind: records\nschema: name, value\n---\n"
                    : json ? "{\n  \"$meta\": { \"kind\": \"records\" },\n  \"schema\": [\"name\", \"value\"],\n  \"items\": []\n}\n"
                    : yaml ? "$meta:\n  kind: records\nschema: [name, value]\nitems: []\n"
                    : "";
            case "sheet" -> json
                    ? "{\n  \"$meta\": { \"kind\": \"sheet\" },\n  \"schema\": [\"A\", \"B\", \"C\"],\n  \"rows\": 5,\n  \"cells\": []\n}\n"
                    : "$meta:\n  kind: sheet\nschema: [A, B, C]\nrows: 5\ncells: []\n";
            case "graph" -> json
                    ? "{\n  \"$meta\": { \"kind\": \"graph\" },\n  \"graph\": { \"directed\": true },\n  \"nodes\": [],\n  \"edges\": []\n}\n"
                    : "$meta:\n  kind: graph\ngraph:\n  directed: true\nnodes: []\nedges: []\n";
            case "chart" -> json
                    ? "{\n  \"$meta\": { \"kind\": \"chart\" },\n  \"chart\": { \"chartType\": \"line\", \"title\": \"New Chart\" },\n  \"xAxis\": { \"type\": \"category\" },\n  \"yAxis\": { \"type\": \"value\" },\n  \"series\": [\n    { \"name\": \"Series 1\", \"data\": [\n      { \"x\": \"A\", \"y\": 10 },\n      { \"x\": \"B\", \"y\": 20 },\n      { \"x\": \"C\", \"y\": 15 }\n    ] }\n  ]\n}\n"
                    : "$meta:\n  kind: chart\nchart:\n  chartType: line\n  title: New Chart\nxAxis:\n  type: category\nyAxis:\n  type: value\nseries:\n  - name: Series 1\n    data:\n      - { x: A, y: 10 }\n      - { x: B, y: 20 }\n      - { x: C, y: 15 }\n";
            case "slides" -> md
                    ? "---\nkind: slides\nslides:\n  theme: default\n  aspect: \"16:9\"\n  paginate: true\n---\n\n# First slide\n\nWelcome to your deck.\n\n---\n\n## Second slide\n\n- bullet one\n- bullet two\n"
                    : json ? "{\n  \"$meta\": { \"kind\": \"slides\" },\n  \"slides\": { \"theme\": \"default\", \"aspect\": \"16:9\", \"paginate\": true },\n  \"items\": [\n    \"# First slide\\n\\nWelcome to your deck.\",\n    \"## Second slide\\n\\n- bullet one\\n- bullet two\"\n  ]\n}\n"
                    : "$meta:\n  kind: slides\nslides:\n  theme: default\n  aspect: \"16:9\"\n  paginate: true\nitems:\n  - |\n    # First slide\n\n    Welcome to your deck.\n  - |\n    ## Second slide\n\n    - bullet one\n    - bullet two\n";
            case "data" -> json
                    ? "{\n  \"$meta\": { \"kind\": \"data\" }\n}\n"
                    : "$meta:\n  kind: data\n";
            default -> md ? "---\nkind: " + kind + "\n---\n"
                    : json ? "{\n  \"$meta\": { \"kind\": \"" + kind + "\" }\n}\n"
                    : "$meta:\n  kind: " + kind + "\n";
        };
    }
}
