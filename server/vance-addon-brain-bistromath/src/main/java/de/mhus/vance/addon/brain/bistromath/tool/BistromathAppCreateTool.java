package de.mhus.vance.addon.brain.bistromath.tool;

import de.mhus.vance.addon.brain.bistromath.BistromathApplication;
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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * One-shot bootstrap of an {@code app: custom} folder — manifest, one view and
 * one program: a Hello World that runs.
 *
 * <p>This is the only tool the runtime adds, and the only REST-shaped thing it
 * needed. Everything after creation is done with the ordinary document tools,
 * because everything after creation <em>is</em> a document: a view is edited
 * with {@code doc_write} and checked with the generic {@code app_rebuild}, and
 * the app's data is read by its own program through the document API. A
 * {@code bistromath_view_write} would be a second way to write a document,
 * with its own idea of what a valid one looks like.
 */
@Component
@Slf4j
public class BistromathAppCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "Target folder (e.g. 'apps/invoices'). "
                                + "_app.yaml, main.yaml (the view) and main.js (the "
                                + "program) are written inside it."));
                put("title", Map.of("type", "string"));
                put("description", Map.of("type", "string"));
                put("overwrite", Map.of("type", "boolean",
                        "description", "Replace an existing manifest. Default false."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final BistromathApplication application;

    public BistromathAppCreateTool(EddieContext eddieContext,
                                   BistromathApplication application) {
        this.eddieContext = eddieContext;
        this.application = application;
    }

    @Override public String name() { return "bistromath_app_create"; }

    @Override
    public String description() {
        return "Create a custom application — a small program made of documents: a view "
                + "document holds a widget tree (toolbar, button, text, table, form) and a "
                + "main.js holds the behaviour, running sandboxed in the browser. Scaffolds "
                + "a working Hello World, so the app does something the moment it opens. Use "
                + "this when the user wants a small tool and no existing app type fits — a "
                + "register, a converter, a form over records, a dashboard. Data is not "
                + "declared anywhere: the program reads documents with the ordinary document "
                + "API. Edit the view and main.js afterwards with the document tools, then "
                + "run app_rebuild to check them.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "bistromath");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
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

        log.info("BistromathAppCreateTool folder='{}'", folder);
        return result.toMap();
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }
}
