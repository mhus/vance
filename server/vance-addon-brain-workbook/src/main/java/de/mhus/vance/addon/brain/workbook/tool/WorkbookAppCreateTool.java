package de.mhus.vance.addon.brain.workbook.tool;

import de.mhus.vance.addon.brain.workbook.WorkbookApplication;
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
 * Bootstrap a {@code app: workbook} folder — writes the
 * {@code _app.yaml} manifest and runs an initial {@code refresh()} so a
 * {@code _index.md} exists from the first turn.
 */
@Component
@Slf4j
public class WorkbookAppCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "Target folder (e.g. 'studium-ws26'). "
                                + "_app.yaml is written inside it."));
                put("title", Map.of("type", "string"));
                put("description", Map.of("type", "string"));
                put("landingPage", Map.of("type", "string",
                        "description", "Optional default page to mount when "
                                + "the workbook opens — e.g. 'ueberblick.workpage.md'."));
                put("indexStyle", Map.of("type", "string",
                        "description", "'cards' (default) or 'list'."));
                put("overwrite", Map.of("type", "boolean",
                        "description", "Replace an existing manifest. Default false."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final WorkbookApplication application;

    public WorkbookAppCreateTool(EddieContext eddieContext,
                                  WorkbookApplication application) {
        this.eddieContext = eddieContext;
        this.application = application;
    }

    @Override public String name() { return "workbook_app_create"; }

    @Override
    public String description() {
        return "Bootstrap a Workbook folder. Writes the _app.yaml manifest "
                + "(kind: application, app: workbook) and seeds an empty "
                + "_index.md so the folder is immediately mountable. Add "
                + "pages afterwards with workpage_create(path=\"folder/<slug>\", ...) "
                + "and rerun app_rebuild('folder') to refresh the index.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document", "workbook", "application");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        boolean overwrite = paramBoolean(params, "overwrite");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);

        Map<String, Object> appParams = new LinkedHashMap<>(params);
        appParams.remove("folder");
        appParams.remove("overwrite");
        appParams.remove("projectId");

        VanceApplication.CreateContext cc = new VanceApplication.CreateContext(
                ctx.tenantId(), project.getName(), folder,
                ctx.userId(), ctx.processId(),
                overwrite, appParams);
        VanceApplication.CreateResult result = application.create(cc);

        log.info("WorkbookAppCreateTool folder='{}' title='{}'",
                folder, paramString(params, "title"));
        return result.toMap();
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static boolean paramBoolean(Map<String, Object> params, String key) {
        if (params == null) return false;
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }
}
