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

/** Edit one entry of a link list — group, title, teaser, tags, note. */
@Component
@Slf4j
public class LinksEntryUpdateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "The link-list folder (holding _app.yaml)."));
                put("url", Map.of("type", "string",
                        "description", "Which entry — the link as it is stored."));
                put("group", Map.of("type", "string",
                        "description", "Move to this group. Empty string moves it out of "
                                + "every group."));
                put("title", Map.of("type", "string",
                        "description", "New title. Empty string re-fetches the page's own "
                                + "title instead of clearing it."));
                put("teaser", Map.of("type", "string",
                        "description", "New teaser. Empty string drops the override so the "
                                + "page's own description shows again."));
                put("tags", Map.of("type", "array",
                        "description", "Replaces the whole tag list. Omit to leave it alone.",
                        "items", Map.of("type", "string")));
                put("note", Map.of("type", "string",
                        "description", "New remark. Empty string clears it."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder", "url"));

    private final EddieContext eddieContext;
    private final LinksManifestOps manifestOps;

    public LinksEntryUpdateTool(EddieContext eddieContext, LinksManifestOps manifestOps) {
        this.eddieContext = eddieContext;
        this.manifestOps = manifestOps;
    }

    @Override public String name() { return "links_entry_update"; }

    @Override
    public String description() {
        return "Change one entry of a link list. Fields you omit stay as they are; passing an "
                + "empty string clears that field. Use this to sort a link into a group or to "
                + "write a teaser the user dictated.";
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
        manifestOps.updateEntry(ctx.tenantId(), project.getName(), folder,
                url, LinksToolSupport.fields(params), ctx.userId());

        log.info("LinksEntryUpdateTool folder='{}' url='{}'", folder, url);
        return Map.of("folder", folder, "url", url, "updated", true);
    }
}
