package de.mhus.vance.addon.brain.gtd.tool;

import de.mhus.vance.addon.brain.gtd.GtdApplication;
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

/** Bootstrap an {@code app: gtd} folder — manifest + initial refresh. */
@Component
@Slf4j
public class GtdAppCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "Target folder (e.g. 'gtd'). _app.yaml is written inside it."));
                put("title", Map.of("type", "string"));
                put("description", Map.of("type", "string"));
                put("overwrite", Map.of("type", "boolean"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final GtdApplication application;

    public GtdAppCreateTool(EddieContext eddieContext, GtdApplication application) {
        this.eddieContext = eddieContext;
        this.application = application;
    }

    @Override public String name() { return "gtd_app_create"; }

    @Override
    public String description() {
        return "Bootstrap a GTD (Getting Things Done, Things-style) folder. Writes the "
                + "_app.yaml manifest (kind: application, app: gtd) and generates "
                + "_today.md / _upcoming.md / _stats.yaml. Capture actions afterwards with "
                + "gtd_capture, process them into buckets by setting `when`.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("eddie", "write", "document", "gtd", "application"); }
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
                ctx.tenantId(), project.getName(), folder, ctx.userId(), ctx.processId(),
                overwrite, appParams);
        return application.create(cc).toMap();
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
    private static boolean paramBoolean(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }
}
