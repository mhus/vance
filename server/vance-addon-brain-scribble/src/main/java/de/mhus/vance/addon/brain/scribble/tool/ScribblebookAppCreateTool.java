package de.mhus.vance.addon.brain.scribble.tool;

import de.mhus.vance.addon.brain.scribble.ScribblebookApplication;
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

/** One-shot bootstrap of an {@code app: scribblebook} folder. */
@Component
@Slf4j
public class ScribblebookAppCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            new LinkedHashMap<String, Object>() {
                {
                    put(
                            "folder",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Target folder (e.g. 'notizbuch'). " + "_app.yaml is written inside it."));
                    put("title", Map.of("type", "string"));
                    put("description", Map.of("type", "string"));
                    put(
                            "landingPage",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Optional default sheet to open (e.g. 'meeting.scribble.yaml')."));
                    put(
                            "pages",
                            Map.of(
                                    "type",
                                    "array",
                                    "description",
                                    "Optional initial sheets, each `{ title, slug? }`.",
                                    "items",
                                    Map.of("type", "object")));
                    put(
                            "overwrite",
                            Map.of("type", "boolean", "description", "Replace an existing manifest. Default false."));
                    put("projectId", Map.of("type", "string"));
                }
            },
            "required",
            List.of("folder"));

    private final EddieContext eddieContext;
    private final ScribblebookApplication application;

    public ScribblebookAppCreateTool(EddieContext eddieContext, ScribblebookApplication application) {
        this.eddieContext = eddieContext;
        this.application = application;
    }

    @Override
    public String name() {
        return "scribblebook_app_create";
    }

    @Override
    public String description() {
        return "Create a scribblebook — a folder container of handwritten `kind: "
                + "scribble` sheets, with an auto-generated `_index.md`. Optionally "
                + "seed initial sheets. Use this instead of hand-writing `_app.yaml`.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document", "scribble");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = ScribbleToolSupport.paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        boolean overwrite = Boolean.TRUE.equals(params.get("overwrite"));

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);

        Map<String, Object> appParams = new LinkedHashMap<>(params);
        appParams.remove("folder");
        appParams.remove("overwrite");
        appParams.remove("projectId");

        VanceApplication.CreateContext cc = new VanceApplication.CreateContext(
                ctx.tenantId(), project.getName(), folder, ctx.userId(), ctx.processId(), overwrite, appParams);
        VanceApplication.CreateResult result = application.create(cc);

        log.info(
                "ScribblebookAppCreateTool folder='{}' title='{}'",
                folder,
                ScribbleToolSupport.paramString(params, "title"));
        return result.toMap();
    }
}
