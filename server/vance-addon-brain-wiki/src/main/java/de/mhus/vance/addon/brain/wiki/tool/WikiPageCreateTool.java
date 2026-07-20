package de.mhus.vance.addon.brain.wiki.tool;

import de.mhus.vance.addon.brain.wiki.WikiService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Create a new wiki page ({@code kind: workpage}) inside a wiki folder /
 * space. The slug is derived from the title; the path is made unique.
 * Seeds a minimal workpage body — add content via the {@code workpage_*}
 * tools or link to it with {@code [[Title]]} from other pages.
 */
@Component
@Slf4j
public class WikiPageCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "The wiki root folder (contains _app.yaml)."));
                put("title", Map.of("type", "string"));
                put("space", Map.of("type", "string",
                        "description", "Optional sub-folder / space (e.g. 'guides' or "
                                + "'guides/setup'). Defaults to the wiki root."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder", "title"));

    private final EddieContext eddieContext;
    private final WikiService wikiService;

    public WikiPageCreateTool(EddieContext eddieContext, WikiService wikiService) {
        this.eddieContext = eddieContext;
        this.wikiService = wikiService;
    }

    @Override public String name() { return "wikipage_create"; }

    @Override
    public String description() {
        return "Create a new wiki page (kind: workpage) inside a wiki folder. "
                + "Stored as Markdown with a $meta.kind: workpage header. The slug "
                + "is derived from the title; pass `space` to place it in a "
                + "sub-folder. Link to it from other pages with [[Title]]. Run "
                + "app_rebuild('folder') afterwards to refresh the indexes + backlinks.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "wiki", "workpage");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        String title = paramString(params, "title");
        if (title == null) throw new ToolException("title is required");
        String space = paramString(params, "space");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        DocumentDocument stored = wikiService.createPage(
                ctx.tenantId(), project.getName(), folder, space, title, ctx.userId());

        log.info("WikiPageCreateTool folder='{}' space='{}' path='{}'",
                folder, space, stored.getPath());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", stored.getPath());
        result.put("id", stored.getId());
        result.put("title", title);
        if (space != null) result.put("space", space);
        result.put("nextStep", "Add content with `workpage_block_append` / edit blocks, "
                + "then `app_rebuild('" + folder + "')` to refresh indexes + backlinks.");
        return result;
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
