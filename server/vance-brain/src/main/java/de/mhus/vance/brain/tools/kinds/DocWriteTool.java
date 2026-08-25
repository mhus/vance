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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Canonical "write a document" tool — creates a new document at
 * {@code path}, or overwrites the whole body of the one already there.
 * The single canonical full-body write: one tool, one decision the LLM
 * has to make (which {@code kind}).
 *
 * <p>Absorbed the former {@code doc_create}: {@code doc_write} is the
 * generic full-body write (create-or-overwrite by path), while
 * {@code doc_create_kind} stays the odd one out — a true create that
 * only stamps a typed starter stub. Partial edits live in the sibling
 * tools: {@code doc_edit} for surgical find-and-replace,
 * {@code doc_replace_lines} for line-range patches. Full overwrite is
 * the bluntest option and easiest to get wrong on a doc the user is
 * actively viewing — keep the smallest tool that fits.
 *
 * <p>The {@code kind} parameter is optional. On overwrite a blank
 * {@code kind} keeps the document's existing kind; on create a blank
 * defaults to {@code text}. A {@link KindResolver} silently coerces
 * blanks, typos, and unknown values to the nearest registered kind
 * (or {@code "text"}) — never throws.
 *
 * <p>Upsert semantics: existing document at the same {@code path}
 * gets its content / title / tags / mime / kind updated (lineage,
 * createdAt, summary preserved). A kind change is allowed; if the
 * caller-supplied kind is unresolvable, the existing kind is kept.
 */
@Component
@RequiredArgsConstructor
public class DocWriteTool implements Tool {

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
                                    + "this path already exists its body is "
                                    + "overwritten; otherwise it is created."),
                    "kind", Map.of(
                            "type", "string",
                            "description", "Document kind — pick by "
                                    + "content shape, NOT by file extension. "
                                    + "Known kinds: diagram (Mermaid: "
                                    + "flowchart / sequence / state / ER / "
                                    + "gantt / gitGraph / journey / pie / "
                                    + "C4 / timeline), mindmap (radial "
                                    + "bullets), chart (numeric data with "
                                    + "axes), graph (node/edge network), "
                                    + "records (typed table), sheet "
                                    + "(spreadsheet cells), list, checklist, "
                                    + "tree, slides (deck), application "
                                    + "(kit-defined app), data (raw JSON), "
                                    + "formula (KaTeX/mhchem-rendered math "
                                    + "or chemistry), schema. Addons can add more kinds — "
                                    + "the registry is open. Name the kind that "
                                    + "matches the content you are writing; a body "
                                    + "whose main content is a fenced diagram, chart "
                                    + "or graph block is one of those kinds. This is "
                                    + "about the CONTENT, not the file format — "
                                    + "'markdown file' is not a kind. Unsure which "
                                    + "one fits? Leave it out, or ask "
                                    + "how_do_i('which document kind fits <your "
                                    + "content>') — do not guess. On overwrite, omit "
                                    + "to keep the existing kind."),
                    "content", Map.of(
                            "type", "string",
                            "description", "Document body. Replaces whatever "
                                    + "was there before. For typed kinds the "
                                    + "shape is kind-specific (see "
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
            "required", List.of("path", "content"));

    private final KindToolSupport support;
    private final KindResolver kindResolver;
    private final KindRegistry kindRegistry;
    private final DocumentLinkBuilder linkBuilder;

    @Override public String name() { return "doc_write"; }

    @Override public String description() {
        return "Write a document — create new at the given path, or "
                + "overwrite the whole body if it already exists. Pick "
                + "`kind` by content shape (NOT file extension): `diagram` "
                + "for Mermaid, `mindmap` for radial outlines, `chart` for "
                + "numeric data, `graph` for node/edge networks, `records` "
                + "for tables, `slides` for decks, `formula` for math/chemistry "
                + "formulas (KaTeX), `text` for free-form "
                + "prose. The Web-UI renders typed kinds into their "
                + "specific editors; `kind=text` is plain markdown. `kind` "
                + "is optional — on overwrite the existing kind is kept, on "
                + "create it defaults to `text`. Full overwrite is the "
                + "bluntest write; prefer `doc_edit` or `doc_replace_lines` "
                + "when only a portion needs to change. Body shape is "
                + "kind-specific — see `manual_read('kind-<kind>')` when "
                + "unsure.";
    }

    @Override public boolean primary() { return true; }

    @Override public Set<String> labels() {
        return Set.of("doc-management", "text-edit", "eddie", "write", "document");
    }

    @Override
    public Set<String> prakLabels() {
        return Set.of("knowledge", "documents");
    }

    /**
     * The static schema, with the **actually registered** kinds appended to the
     * {@code kind} description.
     *
     * <p>The prose list above says what each built-in kind is *for*, which no
     * generated list can replace — and then claims the registry is open. That
     * claim was only half true: an addon kind (`app-view`, `finance-tree`, …)
     * was reachable but unnameable, so an agent had to be told about it out of
     * band or conclude it did not exist. Naming them costs one line of prompt
     * and removes a whole class of "that is not supported" answers.
     *
     * <p>Computed once. The registry is fixed after construction, so this is
     * not per-turn work and the prompt prefix stays stable — which matters,
     * because a description that changed between turns would break the cache.
     */
    @Override public Map<String, Object> paramsSchema() {
        Map<String, Object> cached = schemaWithKinds;
        if (cached == null) {
            cached = buildSchemaWithKinds();
            schemaWithKinds = cached;
        }
        return cached;
    }

    private volatile @Nullable Map<String, Object> schemaWithKinds;

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSchemaWithKinds() {
        // The base schema when there is no registry to ask. `paramsSchema()` is
        // a *description* method: it is called while the prompt is assembled and
        // by tooling that builds a tool without a full context. Throwing there
        // costs the tool its place on the surface entirely, which is a far worse
        // outcome than a description without the appended list.
        if (kindRegistry == null) return SCHEMA;
        List<String> names = new ArrayList<>(kindRegistry.names());
        Collections.sort(names);
        if (names.isEmpty()) return SCHEMA;

        Map<String, Object> properties =
                new LinkedHashMap<>((Map<String, Object>) SCHEMA.get("properties"));
        Map<String, Object> kind =
                new LinkedHashMap<>((Map<String, Object>) properties.get("kind"));
        kind.put("description", kind.get("description")
                + " Registered here: " + String.join(", ", names) + ".");
        properties.put("kind", kind);

        Map<String, Object> out = new LinkedHashMap<>(SCHEMA);
        out.put("properties", properties);
        return Map.copyOf(out);
    }

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
        String resolvedKind = kindResolver.resolve(requestedKind, existingKind, content);
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
        // Kind-aware post-write self-check folded into the save (advisory):
        // the resolved kind is stamped above, so structural mistakes in the
        // body come straight back as feedback instead of persisting silently.
        Map<String, Object> validation = support.validateWritten(result, content, ctx);
        if (validation != null) out.put("validation", validation);
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
