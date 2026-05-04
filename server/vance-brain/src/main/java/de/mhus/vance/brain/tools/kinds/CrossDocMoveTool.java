package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Move a document from one project to another. Implementation is
 * copy-then-trash-source: the source ends up in its own project's
 * trash (via {@code DocumentService.trash}); the new copy lives in
 * the target project. Not perfectly atomic — if the source-trash
 * step fails after the copy succeeds, the document exists in both
 * places and a manual cleanup is needed (the copy is the "real" one).
 */
@Component
@RequiredArgsConstructor
public class CrossDocMoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("targetProjectId", "newPath"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("targetProjectId", Map.of("type", "string",
                "description", "Project name to move into."));
        p.put("newPath", Map.of("type", "string",
                "description", "Path inside the target project. Must not exist."));
        p.put("title", Map.of("type", "string",
                "description", "Optional title override; defaults to the source's title."));
        return p;
    }

    private final KindToolSupport support;
    private final ProjectService projectService;

    @Override public String name() { return "cross_doc_move"; }
    @Override public String description() {
        return "Move a document into a different project. Implemented as copy-to-target + trash-source "
                + "(the source ends up in its project's `_vance/bin/`). The new copy gets a fresh id; "
                + "the source id stays valid (now points at the trashed entry, restorable for 30s by the "
                + "buffer-flush window).";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("cross-project"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument source = support.requireInline(support.loadDocument(params, ctx));
        String targetProjectName = KindToolSupport.requireString(params, "targetProjectId");
        String newPath = KindToolSupport.requireString(params, "newPath");
        String title = KindToolSupport.paramString(params, "title");

        ProjectDocument target = projectService.findByTenantAndName(ctx.tenantId(), targetProjectName)
                .orElseThrow(() -> new ToolException(
                        "Target project '" + targetProjectName + "' not found in tenant"));
        if (target.getKind() == ProjectKind.SYSTEM
                && !targetProjectName.equals(ProjectService.SYSTEM_NAME_PREFIX + "vance")) {
            throw new ToolException("Cannot move into SYSTEM project '" + targetProjectName + "'");
        }

        support.buffer().flush(ctx.processId(), source.getId());
        DocumentDocument fresh = support.buffer().read(ctx.processId(), source.getId());
        if (fresh == null) throw new ToolException("Source document disappeared");

        DocumentDocument copy;
        try {
            copy = support.documentService().create(
                    ctx.tenantId(),
                    target.getName(),
                    newPath,
                    title != null ? title : fresh.getTitle(),
                    fresh.getTags() != null ? List.copyOf(fresh.getTags()) : null,
                    fresh.getMimeType(),
                    new ByteArrayInputStream(fresh.getInlineText().getBytes(StandardCharsets.UTF_8)),
                    ctx.userId());
        } catch (DocumentService.DocumentAlreadyExistsException e) {
            throw new ToolException(e.getMessage(), e);
        }

        DocumentDocument trashed;
        try {
            trashed = support.documentService().trash(fresh.getId());
        } catch (RuntimeException e) {
            // Copy succeeded but trash failed — the source is still
            // in place, the target also has the new copy. Tell the
            // user explicitly so they can manually clean up.
            throw new ToolException("Cross-project move partially succeeded: copy created at "
                    + copy.getProjectId() + ":" + copy.getPath() + " (id=" + copy.getId()
                    + "), but trashing the source failed (" + e.getMessage()
                    + "). Source still alive at " + fresh.getProjectId() + ":" + fresh.getPath()
                    + ". Use doc_delete on the source to finish manually.", e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceId", fresh.getId());
        out.put("sourceProjectId", fresh.getProjectId());
        out.put("sourceTrashPath", trashed.getPath());
        out.put("newId", copy.getId());
        out.put("newProjectId", copy.getProjectId());
        out.put("newPath", copy.getPath());
        return out;
    }
}
