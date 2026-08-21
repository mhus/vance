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

/** Add a link to a link list. */
@Component
@Slf4j
public class LinksEntryAddTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "The link-list folder (holding _app.yaml)."));
                put("url", Map.of("type", "string",
                        "description", "The link. http(s) only; a missing scheme "
                                + "is read as https."));
                put("group", Map.of("type", "string",
                        "description", "Optional group heading. A group that does not "
                                + "exist yet is created."));
                put("title", Map.of("type", "string",
                        "description", "Optional title. Leave it out — the page's own "
                                + "title is fetched and stored automatically."));
                put("teaser", Map.of("type", "string",
                        "description", "Optional own teaser text. Leave it out unless the "
                                + "user asked for one: without it the page's own description "
                                + "is shown live."));
                put("tags", Map.of("type", "array",
                        "description", "Optional labels for filtering.",
                        "items", Map.of("type", "string")));
                put("note", Map.of("type", "string",
                        "description", "Optional remark on why this list has the link. "
                                + "Distinct from the teaser, which describes the page."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder", "url"));

    private final EddieContext eddieContext;
    private final LinksManifestOps manifestOps;

    public LinksEntryAddTool(EddieContext eddieContext, LinksManifestOps manifestOps) {
        this.eddieContext = eddieContext;
        this.manifestOps = manifestOps;
    }

    @Override public String name() { return "links_entry_add"; }

    @Override
    public String description() {
        return "Add an external link to a link list. Idempotent — adding the same URL twice "
                + "is a no-op. Title is fetched from the page; teaser and picture resolve "
                + "themselves live, so only pass them when the user dictated them.";
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
        boolean added = manifestOps.addEntry(ctx.tenantId(), project.getName(), folder,
                url, LinksToolSupport.fields(params), ctx.userId());

        log.info("LinksEntryAddTool folder='{}' url='{}' added={}", folder, url, added);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("folder", folder);
        result.put("url", url);
        result.put("added", added);
        if (!added) result.put("note", "Already in the list — nothing changed.");
        return result;
    }
}
