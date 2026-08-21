package de.mhus.vance.addon.brain.links.tool;

import de.mhus.vance.addon.brain.links.LinksApplication;
import de.mhus.vance.brain.applications.VanceApplication;
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

/** One-shot bootstrap of an {@code app: links} folder. */
@Component
@Slf4j
public class LinksAppCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "Target folder (e.g. 'reading/links'). "
                                + "_app.yaml is written inside it."));
                put("title", Map.of("type", "string"));
                put("description", Map.of("type", "string"));
                put("groups", Map.of("type", "array",
                        "description", "Optional group headings, in display order. "
                                + "A group may be empty — links are added afterwards.",
                        "items", Map.of("type", "string")));
                put("overwrite", Map.of("type", "boolean",
                        "description", "Replace an existing manifest. Default false."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final LinksApplication application;

    public LinksAppCreateTool(EddieContext eddieContext, LinksApplication application) {
        this.eddieContext = eddieContext;
        this.application = application;
    }

    @Override public String name() { return "links_app_create"; }

    @Override
    public String description() {
        return "Create a link list — a folder app that keeps external URLs as an ordered, "
                + "grouped collection of preview cards (title, teaser and picture come from "
                + "the linked page). Use this instead of hand-writing `_app.yaml`. For "
                + "references to documents inside the project use a binder instead.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "links");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = LinksToolSupport.paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        boolean overwrite = Boolean.TRUE.equals(params.get("overwrite"));

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);

        Map<String, Object> appParams = new LinkedHashMap<>(params);
        appParams.remove("folder");
        appParams.remove("overwrite");
        appParams.remove("projectId");

        VanceApplication.CreateResult result = application.create(
                new VanceApplication.CreateContext(ctx.tenantId(), project.getName(), folder,
                        ctx.userId(), ctx.processId(), overwrite, appParams));

        log.info("LinksAppCreateTool folder='{}'", folder);
        return result.toMap();
    }
}
