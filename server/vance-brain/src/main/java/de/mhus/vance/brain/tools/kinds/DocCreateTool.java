package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.KindRegistry;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Canonical "save a document" tool — creates a new document with the
 * given {@code kind}, or overwrites the one at {@code path} if it
 * already exists. The single canonical "save a document" tool: one
 * tool, one decision the LLM has to make (which {@code kind}).
 *
 * <p>The {@code kind} parameter is marked required in the schema so
 * the model is forced to think about it instead of defaulting to
 * "just text". Server-side a {@link KindResolver} silently coerces
 * blanks, typos, and unknown values to the nearest registered kind
 * (or {@code "text"} as the ultimate fallback) — never throws — but
 * that fallback is intentionally absent from the description so the
 * model treats {@code kind} as mandatory.
 *
 * <p>Upsert semantics: existing document at the same {@code path}
 * gets its content / title / tags / mime / kind updated (lineage,
 * createdAt, summary preserved). A kind change is allowed; if the
 * caller-supplied kind is unresolvable, the existing kind is kept.
 */
@Component
@RequiredArgsConstructor
public class DocCreateTool implements Tool {

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
                                    + "e.g. 'reports/q3-summary.md' or "
                                    + "'diagrams/login-flow.md'. If a doc at "
                                    + "this path already exists it gets "
                                    + "overwritten."),
                    "kind", Map.of(
                            "type", "string",
                            "description", "Document kind — pick by content "
                                    + "shape, NOT by file extension. Known "
                                    + "kinds: text (free-form prose / notes / "
                                    + "summaries), diagram (Mermaid: "
                                    + "flowchart / sequence / state / ER / "
                                    + "gantt / gitGraph / journey / pie / "
                                    + "C4 / timeline), mindmap (radial "
                                    + "bullets), chart (numeric data with "
                                    + "axes), graph (node/edge network), "
                                    + "records (typed table), sheet "
                                    + "(spreadsheet cells), list, checklist, "
                                    + "tree, slides (deck), application "
                                    + "(kit-defined app), data (raw JSON), "
                                    + "schema. Addons can add more kinds — "
                                    + "the registry is open. Pick the one "
                                    + "that matches the body shape."),
                    "content", Map.of(
                            "type", "string",
                            "description", "Document body. For typed kinds "
                                    + "the shape is kind-specific (see "
                                    + "`manual_read('kind-<kind>')`)."),
                    "title", Map.of(
                            "type", "string",
                            "description", "Optional human title."),
                    "tags", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "Optional tag list."),
                    "mimeType", Map.of(
                            "type", "string",
                            "description", "Optional MIME type override. "
                                    + "Defaults to a kind-appropriate value "
                                    + "(text/markdown or application/json).")),
            "required", List.of("path", "kind", "content"));

    private final KindToolSupport support;
    private final KindResolver kindResolver;
    private final KindRegistry kindRegistry;
    private final DocumentLinkBuilder linkBuilder;

    @Override public String name() { return "doc_create"; }

    @Override public String description() {
        return "Save a document — create new or overwrite existing at "
                + "the given path. Pick `kind` by content shape (NOT "
                + "file extension): `diagram` for Mermaid, `mindmap` "
                + "for radial outlines, `chart` for numeric data, "
                + "`graph` for node/edge networks, `records` for "
                + "tables, `slides` for decks, `text` for free-form "
                + "prose. The Web-UI renders typed kinds into their "
                + "specific editors; `kind=text` is plain markdown. "
                + "If the path already exists, the content is "
                + "overwritten in place. Body shape is kind-specific "
                + "— see `manual_read('kind-<kind>')` when unsure.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("doc-management", "eddie", "write", "document");
    }

    @Override
    public Set<String> prakLabels() {
        return Set.of("knowledge", "documents");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = KindToolSupport.requireString(params, "path");
        String content = KindToolSupport.paramRawString(params, "content");
        if (content == null) {
            throw new ToolException("'content' is required");
        }
        String requestedKind = KindToolSupport.paramString(params, "kind");
        String title = KindToolSupport.paramString(params, "title");
        String mimeType = KindToolSupport.paramString(params, "mimeType");
        @SuppressWarnings("unchecked")
        List<String> tags = params.get("tags") instanceof List<?> l
                ? l.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : null;

        ProjectDocument project = support.eddieContext().resolveProject(params, ctx, false);
        support.enforceDocWrite(ctx, project.getName(), path,
                de.mhus.vance.shared.permission.Action.CREATE);
        DocumentService docService = support.documentService();

        Optional<DocumentDocument> existing =
                docService.findByPath(ctx.tenantId(), project.getName(), path);
        String existingKind = existing.map(DocumentDocument::getKind).orElse(null);
        String resolvedKind = kindResolver.resolve(requestedKind, existingKind);
        if (mimeType == null) {
            mimeType = defaultMimeFor(resolvedKind);
        }

        DocumentDocument result;
        boolean overwritten;
        if (existing.isPresent()) {
            // Upsert path: content/title/tags/mime in-place; kind below.
            result = docService.update(
                    existing.get().getId(),
                    title,
                    tags,
                    content,
                    /*newPath*/ null,
                    /*autoSummary*/ null,
                    /*summaryDirty*/ null,
                    /*ragEnabled*/ null,
                    mimeType,
                    DocumentService.TOOL_IDENTITY,
                    support.writeActor(ctx, path));
            overwritten = true;
        } else {
            try {
                result = docService.create(
                        ctx.tenantId(),
                        project.getName(),
                        path,
                        title,
                        tags,
                        mimeType,
                        new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                        ctx.userId(),
                        support.writeActor(ctx, path));
            } catch (DocumentService.DocumentAlreadyExistsException e) {
                // Race: findByPath came back empty but create lost to a
                // concurrent insert. Surface a clean ToolException —
                // the next turn can retry.
                throw new ToolException(e.getMessage(), e);
            }
            overwritten = false;
        }

        // Stamp the resolved kind explicitly. The body's front-matter
        // parsing (via applyHeader on update / create) is the usual
        // path, but the LLM occasionally writes a body without the
        // `---kind: <name>---` front-matter — particularly for kinds
        // like `slides` where the `---` separators serve a second
        // purpose. Match `DocCreateKindTool`'s behaviour.
        if (!resolvedKind.equalsIgnoreCase(result.getKind())) {
            docService.setKind(result.getId(), resolvedKind, support.writeActor(ctx, path));
            result.setKind(resolvedKind);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", result.getId());
        out.put("projectId", result.getProjectId());
        out.put("path", result.getPath());
        out.put("kind", result.getKind());
        if (result.getMimeType() != null) out.put("mimeType", result.getMimeType());
        out.put("overwritten", overwritten);
        // Pre-built Markdown link so the LLM can embed the doc into
        // its reply without a second tool round-trip.
        out.put("markdownLink", linkBuilder.linkFor(result, ctx.projectId()));
        return out;
    }

    private static String defaultMimeFor(String kind) {
        return switch (kind) {
            case "list", "checklist", "tree", "mindmap", "records",
                    "slides", "text", "diagram" -> "text/markdown";
            case "sheet", "graph", "chart", "data", "schema" -> "application/json";
            default -> "text/markdown";
        };
    }
}
