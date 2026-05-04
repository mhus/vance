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
 * Copy a document from one project to another. The source remains
 * intact in its project; the copy gets a new id in the target
 * project. Any in-flight buffered changes on the source are flushed
 * first so the copy reflects the latest content.
 */
@Component
@RequiredArgsConstructor
public class CrossDocCopyTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("targetProjectId", "newPath"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("targetProjectId", Map.of("type", "string",
                "description", "Project name to copy into. Use cross_doc_list_projects to discover."));
        p.put("newPath", Map.of("type", "string",
                "description", "Target path inside the destination project. Must not already exist."));
        p.put("title", Map.of("type", "string",
                "description", "Optional title for the copy. Defaults to the source's title."));
        return p;
    }

    private final KindToolSupport support;
    private final ProjectService projectService;

    @Override public String name() { return "cross_doc_copy"; }
    @Override public String description() {
        return "Copy a document into a different project. Source stays put; copy gets a new id in "
                + "the target project. Use cross_doc_list_projects to discover valid target names.";
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
            throw new ToolException("Cannot copy into SYSTEM project '" + targetProjectName + "'");
        }

        // Flush buffer so we copy the in-flight body, not stale disk.
        support.buffer().flush(ctx.processId(), source.getId());
        DocumentDocument fresh = support.buffer().read(ctx.processId(), source.getId());
        if (fresh == null) throw new ToolException("Source document disappeared during copy");

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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceId", fresh.getId());
        out.put("sourceProjectId", fresh.getProjectId());
        out.put("sourcePath", fresh.getPath());
        out.put("newId", copy.getId());
        out.put("newProjectId", copy.getProjectId());
        out.put("newPath", copy.getPath());
        if (copy.getKind() != null) out.put("kind", copy.getKind());
        return out;
    }
}
