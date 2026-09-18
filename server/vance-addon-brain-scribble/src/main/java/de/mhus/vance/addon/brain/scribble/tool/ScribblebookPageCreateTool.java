package de.mhus.vance.addon.brain.scribble.tool;

import de.mhus.vance.addon.brain.scribble.ScribbleService;
import de.mhus.vance.addon.brain.scribble.ScribblebookApplication;
import de.mhus.vance.addon.brain.scribble.ScribblebookFolderReader;
import de.mhus.vance.brain.applications.VanceApplication;
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
import org.springframework.stereotype.Component;

/** Create a single {@code kind: scribble} sheet inside a scribblebook and refresh the index. */
@Component
@Slf4j
public class ScribblebookPageCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            new LinkedHashMap<String, Object>() {
                {
                    put("folder", Map.of("type", "string", "description", "Scribblebook folder."));
                    put("title", Map.of("type", "string"));
                    put(
                            "slug",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "Optional file slug; derived from title if omitted."));
                    put("projectId", Map.of("type", "string"));
                }
            },
            "required",
            List.of("folder"));

    private final EddieContext eddieContext;
    private final ScribbleService scribbleService;
    private final ScribblebookApplication application;

    public ScribblebookPageCreateTool(
            EddieContext eddieContext, ScribbleService scribbleService, ScribblebookApplication application) {
        this.eddieContext = eddieContext;
        this.scribbleService = scribbleService;
        this.application = application;
    }

    @Override
    public String name() {
        return "scribblebook_page_create";
    }

    @Override
    public String description() {
        return "Add a new handwriting sheet to a scribblebook and refresh its index. "
                + "Returns the new sheet path; the user fills it with the pen editor.";
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
        String normalised = ScribblebookFolderReader.normaliseFolder(folder);
        String title = ScribbleToolSupport.paramString(params, "title");
        String slug = ScribbleToolSupport.paramString(params, "slug");
        if (slug == null) slug = ScribblebookApplication.slugify(title != null ? title : "sheet");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        DocumentDocument stored =
                scribbleService.create(ctx.tenantId(), project.getName(), normalised + "/" + slug, title, ctx.userId());

        application.refresh(new VanceApplication.RefreshContext(
                ctx.tenantId(), project.getName(), normalised, ctx.userId(), ctx.processId()));

        log.info("ScribblebookPageCreateTool folder='{}' path='{}'", normalised, stored.getPath());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", stored.getPath());
        result.put("id", stored.getId());
        if (title != null) result.put("title", title);
        result.put(
                "nextStep",
                "The user writes on the sheet with the pen editor; check " + "structure with `scribble_validate(path=\""
                        + stored.getPath() + "\")`.");
        return result;
    }
}
