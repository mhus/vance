package de.mhus.vance.brain.tools.foreign;

import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Move a document between projects (copy-then-trash-source). Needs DELETE on
 * the source and CREATE on the destination, so it is an orchestrator tool
 * (Eddie/Trillian) — a READER-only caller cannot invoke it and it is kept out
 * of Arthur's surface. Not perfectly atomic: if trashing the source fails
 * after the copy commits, the just-created copy is rolled back so the source
 * stays the single authoritative version.
 */
@Component
@RequiredArgsConstructor
public class ForeignDocMoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "fromProjectId", Map.of("type", "string",
                            "description", "Source project name (required)."),
                    "fromPath", Map.of("type", "string",
                            "description", "Path of the source document (required)."),
                    "toProjectId", Map.of("type", "string",
                            "description", "Destination project name. Defaults to your current project."),
                    "toPath", Map.of("type", "string",
                            "description", "Destination path. Defaults to the source path. Must not exist."),
                    "title", Map.of("type", "string",
                            "description", "Optional title override; defaults to the source's title.")),
            "required", List.of("fromProjectId", "fromPath"));

    private final ForeignAccessSupport foreign;

    @Override public String name() { return "foreign_doc_move"; }

    @Override public String description() {
        return "Move a document from one project to another (copy-to-destination + trash-source). "
                + "Needs delete access to the source and create access to the destination. The copy "
                + "gets a fresh id; the source is trashed. Use foreign_doc_copy to keep the original.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public Set<String> labels() { return Set.of("write", "cross-project", "document"); }
    @Override public String searchHint() { return "Move a document between projects"; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String fromProjectId = KindToolSupport.requireString(params, "fromProjectId");
        String fromPath = KindToolSupport.requireString(params, "fromPath");
        String title = KindToolSupport.paramString(params, "title");

        ProjectDocument sourceProject = foreign.resolveForeign(fromProjectId, ctx, Action.READ);
        if (ForeignAccessSupport.reserved(fromPath)) {
            throw new ToolException("Source path '" + fromPath + "' is in a reserved namespace");
        }
        // Move removes the source → gate DELETE on the source document.
        foreign.enforceDoc(ctx, sourceProject.getName(), fromPath, Action.DELETE);
        DocumentDocument source = foreign.documents()
                .findByPath(ctx.tenantId(), sourceProject.getName(), fromPath)
                .orElseThrow(() -> new ToolException("Document '" + fromPath
                        + "' not found in project '" + sourceProject.getName() + "'"));

        ProjectDocument target = foreign.resolveTarget(
                KindToolSupport.paramString(params, "toProjectId"), ctx);
        String toPath = KindToolSupport.paramString(params, "toPath");
        if (toPath == null) toPath = fromPath;
        if (ForeignAccessSupport.reserved(toPath)) {
            throw new ToolException("Destination path '" + toPath + "' is in a reserved namespace");
        }
        foreign.enforceDoc(ctx, target.getName(), toPath, Action.CREATE);

        DocumentDocument copy;
        try {
            copy = foreign.documents().create(
                    ctx.tenantId(),
                    target.getName(),
                    toPath,
                    title != null ? title : source.getTitle(),
                    source.getTags() != null ? List.copyOf(source.getTags()) : null,
                    source.getMimeType(),
                    new ByteArrayInputStream(foreign.readText(source).getBytes(StandardCharsets.UTF_8)),
                    ctx.userId(),
                    foreign.writeActor(ctx, toPath));
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ToolException(e.getMessage(), e);
        }

        DocumentDocument trashed;
        try {
            trashed = foreign.documents().trash(source.getId(), foreign.writeActor(ctx, source.getPath()));
        } catch (RuntimeException e) {
            // Copy committed but trashing the source failed → the document would
            // otherwise live in BOTH projects. Compensating rollback: delete the
            // just-created copy so the still-intact source stays the single
            // authoritative version (Atomare-Operationen rule).
            try {
                foreign.documents().delete(copy.getId(), foreign.writeActor(ctx, toPath));
            } catch (RuntimeException rollbackEx) {
                throw new ToolException("Cross-project move failed and rollback failed: the copy at "
                        + copy.getProjectId() + ":" + copy.getPath() + " (id=" + copy.getId()
                        + ") could not be removed (" + rollbackEx.getMessage()
                        + ") after the source-trash error (" + e.getMessage()
                        + "). Source still alive at " + source.getProjectId() + ":" + source.getPath()
                        + ". Use doc_delete on the copy to finish manually.", e);
            }
            throw new ToolException("Cross-project move aborted: trashing the source failed ("
                    + e.getMessage() + "); the created copy was rolled back, so the document "
                    + "remains only at its source " + source.getProjectId() + ":" + source.getPath()
                    + ". Retry the move.", e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceProjectId", source.getProjectId());
        out.put("sourceTrashPath", trashed.getPath());
        out.put("newId", copy.getId());
        out.put("newProjectId", copy.getProjectId());
        out.put("newPath", copy.getPath());
        return out;
    }
}
