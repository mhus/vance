package de.mhus.vance.addon.brain.workpage.tool;

import de.mhus.vance.addon.brain.workpage.Block;
import de.mhus.vance.addon.brain.workpage.WorkPageService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Create a new {@code kind: workpage} document with an initial block list. */
@Component
@Slf4j
public class WorkPageCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string",
                        "description", "Target document path (without leading slash). "
                                + "Extension is auto-appended to `.workpage.md` if missing."));
                put("title", Map.of("type", "string"));
                put("description", Map.of("type", "string"));
                put("blocks", Map.of("type", "array",
                        "description", "Optional initial block list. Each entry "
                                + "is `{ type, …fields }` — see workpage-blocks manual.",
                        "items", Map.of("type", "object")));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path"));

    private final EddieContext eddieContext;
    private final WorkPageService workPageService;

    public WorkPageCreateTool(EddieContext eddieContext, WorkPageService workPageService) {
        this.eddieContext = eddieContext;
        this.workPageService = workPageService;
    }

    @Override public String name() { return "workpage_create"; }

    @Override
    public String description() {
        return "Create a new linear block-document (kind: workpage). Stored as "
                + "Markdown with a `$meta.kind: workpage` header. Path is "
                + "auto-suffixed with `.workpage.md` if no extension is given. "
                + "Optional `blocks` array seeds the document content.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "workpage");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = WorkPageToolSupport.paramString(params, "path");
        if (path == null) throw new ToolException("path is required");
        String title = WorkPageToolSupport.paramString(params, "title");
        String description = WorkPageToolSupport.paramString(params, "description");

        List<Block> initial = new ArrayList<>();
        for (Map<String, Object> raw : WorkPageToolSupport.paramMapList(params, "blocks")) {
            initial.add(WorkPageService.buildBlock(raw));
        }

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        DocumentDocument stored = workPageService.create(
                ctx.tenantId(), project.getName(), path,
                title, description, initial, ctx.userId());

        log.info("WorkPageCreateTool path='{}' blocks={} title='{}'",
                stored.getPath(), initial.size(), title);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", stored.getPath());
        result.put("id", stored.getId());
        result.put("blockCount", initial.size());
        if (title != null) result.put("title", title);
        result.put("nextStep", "Add more blocks via `workpage_block_append` or "
                + "edit individual blocks via `workpage_block_update`.");
        return result;
    }
}
