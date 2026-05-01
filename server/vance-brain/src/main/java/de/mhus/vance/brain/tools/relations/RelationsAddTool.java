package de.mhus.vance.brain.tools.relations;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentRelation;
import de.mhus.vance.shared.document.DocumentRelationsService;
import de.mhus.vance.shared.document.DocumentService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Append a single relation entry to a YAML file in the project. The default
 * file is {@code relations/default.yaml}; pass {@code file} to target a
 * different one (e.g. {@code relations/papers.yaml}). The tool creates the
 * file with a {@code kind: relations} header on first use.
 *
 * <p>Append-only: the existing body is left verbatim — comments and
 * hand-written entries survive. Strings are single-quoted so paths
 * containing colons or special YAML characters are safe.
 *
 * <p>Limitations:
 * <ul>
 *   <li>Files must fit in the inline-text storage (default 4 KB). For
 *       larger graphs, organise the relations across multiple files.</li>
 *   <li>The target file's {@code kind} must be {@code relations} — the
 *       tool refuses to corrupt a file with a different kind or a
 *       storage-backed file.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RelationsAddTool implements Tool {

    private static final String DEFAULT_FILE = "relations/default.yaml";
    private static final String DEFAULT_TITLE = "Project relations";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "source", Map.of(
                            "type", "string",
                            "description", "Source-document path (e.g. notes/thesis.md)."),
                    "target", Map.of(
                            "type", "string",
                            "description", "Target-document path (e.g. papers/vaswani2017.pdf)."),
                    "type", Map.of(
                            "type", "string",
                            "description",
                            "Relation type (relates_to, cites, extracted_from, derived_from, "
                                    + "produced_by, input_for, version_of). Default: relates_to."),
                    "note", Map.of(
                            "type", "string",
                            "description", "Optional free-text comment about why the relation exists."),
                    "file", Map.of(
                            "type", "string",
                            "description",
                            "Project-relative path to the YAML file. "
                                    + "Default: relations/default.yaml. Created if missing.")),
            "required", List.of("source", "target"));

    private final DocumentRelationsService relationsService;
    private final DocumentService documentService;

    @Override
    public String name() {
        return "relations_add";
    }

    @Override
    public String description() {
        return "Add a document-to-document relation by appending a YAML entry "
                + "to relations/default.yaml (or another file you specify). "
                + "Creates the file with a kind: relations header on first use.";
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
        String projectId = ctx.projectId();
        if (projectId == null) {
            throw new ToolException("Relations tools require a project scope");
        }
        String source = requireString(params, "source");
        String target = requireString(params, "target");
        String type = optionalString(params, "type");
        if (type.isEmpty()) type = DocumentRelation.DEFAULT_TYPE;
        String note = optionalString(params, "note");
        String file = optionalString(params, "file");
        if (file.isEmpty()) file = DEFAULT_FILE;

        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), projectId, file);

        DocumentDocument saved;
        if (existing.isEmpty()) {
            saved = createNew(ctx, projectId, file, source, type, target, note);
        } else {
            saved = appendTo(existing.get(), source, type, target, note);
        }

        // Re-resolve the live count so the agent sees the result of its
        // own write reflected back — listRelations runs the same parser
        // we just persisted into.
        int total = relationsService.listRelations(ctx.tenantId(), projectId).size();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("definedIn", saved.getPath());
        out.put("source", source);
        out.put("type", type);
        out.put("target", target);
        if (!note.isEmpty()) out.put("note", note);
        out.put("totalRelations", total);
        return out;
    }

    private DocumentDocument createNew(
            ToolInvocationContext ctx, String projectId, String file,
            String source, String type, String target, String note) {
        StringBuilder body = new StringBuilder();
        body.append("kind: ").append(DocumentRelationsService.KIND).append('\n');
        body.append("title: ").append(yamlScalar(DEFAULT_TITLE)).append('\n');
        body.append("---\n");
        appendEntry(body, source, type, target, note);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try {
            return documentService.create(
                    ctx.tenantId(),
                    projectId,
                    file,
                    DEFAULT_TITLE,
                    null,
                    "application/yaml",
                    new ByteArrayInputStream(bytes),
                    ctx.userId());
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            // Race with a concurrent create — fall back to append on the
            // newly-created file.
            return documentService.findByPath(ctx.tenantId(), projectId, file)
                    .map(d -> appendTo(d, source, type, target, note))
                    .orElseThrow(() -> new ToolException(
                            "Failed to create relations file '" + file + "': " + e.getMessage(), e));
        } catch (RuntimeException e) {
            throw new ToolException(
                    "Failed to create relations file '" + file + "': " + e.getMessage(), e);
        }
    }

    private DocumentDocument appendTo(
            DocumentDocument doc,
            String source, String type, String target, String note) {
        String inline = doc.getInlineText();
        if (inline == null) {
            throw new ToolException(
                    "Relations file '" + doc.getPath() + "' is storage-backed; "
                            + "the agent only edits inline files.");
        }
        if (!DocumentRelationsService.KIND.equals(doc.getKind())) {
            throw new ToolException(
                    "File '" + doc.getPath() + "' has kind='" + doc.getKind()
                            + "', expected '" + DocumentRelationsService.KIND
                            + "'. Pick a different `file` parameter or fix the file's header.");
        }
        StringBuilder body = new StringBuilder(inline);
        if (body.length() > 0 && body.charAt(body.length() - 1) != '\n') {
            body.append('\n');
        }
        appendEntry(body, source, type, target, note);

        try {
            return documentService.update(
                    doc.getId(),
                    null,
                    null,
                    body.toString(),
                    null);
        } catch (RuntimeException e) {
            throw new ToolException(
                    "Failed to append relation to '" + doc.getPath() + "': " + e.getMessage(), e);
        }
    }

    private static void appendEntry(
            StringBuilder body, String source, String type, String target, String note) {
        body.append("- source: ").append(yamlScalar(source)).append('\n');
        body.append("  type: ").append(yamlScalar(type)).append('\n');
        body.append("  target: ").append(yamlScalar(target)).append('\n');
        if (!note.isEmpty()) {
            body.append("  note: ").append(yamlScalar(note)).append('\n');
        }
    }

    /**
     * Single-quoted YAML scalar — single-quote style only needs {@code '}
     * doubled, no other escaping. Safe for paths with colons, leading
     * dashes, or any other YAML-special characters.
     */
    private static String yamlScalar(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String requireString(Map<String, Object> params, String key) {
        String v = optionalString(params, key);
        if (v.isEmpty()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return v;
    }

    private static String optionalString(Map<String, Object> params, String key) {
        if (params == null) return "";
        Object v = params.get(key);
        if (!(v instanceof String s)) return "";
        return s.trim();
    }
}
