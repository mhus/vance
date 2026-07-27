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
 * Copy a document from one project to another within the tenant. The common
 * case ("copy from project X into here") omits {@code toProjectId}, defaulting
 * the destination to the caller's current project. Direction is data, not tool
 * choice: the read-only guarantee for a READER-only caller falls out of the
 * permission model — READ on the source, CREATE on the destination — so such a
 * caller can pull out of a foreign project but never write into one. Fresh
 * copy: new id in the destination, no cross-project lineage link.
 */
@Component
@RequiredArgsConstructor
public class ForeignDocCopyTool implements Tool {

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
                            "description", "Optional title for the copy. Defaults to the source's title.")),
            "required", List.of("fromProjectId", "fromPath"));

    private final ForeignAccessSupport foreign;

    @Override public String name() { return "foreign_doc_copy"; }

    @Override public String description() {
        return "Copy a document from another project into (by default) your current project. "
                + "`toProjectId` overrides the destination. Needs read access to the source and "
                + "create access to the destination. Fresh copy — new id, no lineage link.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public Set<String> labels() { return Set.of("write", "cross-project", "document"); }
    @Override public String searchHint() { return "Copy a document from another project into this one"; }
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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceProjectId", source.getProjectId());
        out.put("sourcePath", source.getPath());
        out.put("newId", copy.getId());
        out.put("newProjectId", copy.getProjectId());
        out.put("newPath", copy.getPath());
        if (copy.getKind() != null) out.put("kind", copy.getKind());
        return out;
    }
}
