package de.mhus.vance.addon.brain.links.tool;

import de.mhus.vance.addon.brain.links.LinksManifestOps;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Drop a link from a link list. */
@Component
@Slf4j
public class LinksEntryRemoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "The link-list folder (holding _app.yaml)."));
                put("url", Map.of("type", "string",
                        "description", "Which entry — the link as it is stored."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder", "url"));

    private final EddieContext eddieContext;
    private final LinksManifestOps manifestOps;

    public LinksEntryRemoveTool(EddieContext eddieContext, LinksManifestOps manifestOps) {
        this.eddieContext = eddieContext;
        this.manifestOps = manifestOps;
    }

    @Override public String name() { return "links_entry_remove"; }

    @Override
    public String description() {
        return "Remove a link from a link list. Only the entry goes — nothing is deleted "
                + "anywhere else, the list merely stops pointing at the page.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "links");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = LinksToolSupport.paramString(params, "folder");
        String url = LinksToolSupport.paramString(params, "url");
        if (folder == null) throw new ToolException("folder is required");
        if (url == null) throw new ToolException("url is required");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        manifestOps.removeEntry(ctx.tenantId(), project.getName(), folder, url, ctx.userId());

        log.info("LinksEntryRemoveTool folder='{}' url='{}'", folder, url);
        return Map.of("folder", folder, "url", url, "removed", true);
    }
}
