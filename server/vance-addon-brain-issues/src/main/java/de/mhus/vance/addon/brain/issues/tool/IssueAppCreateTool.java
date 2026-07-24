package de.mhus.vance.addon.brain.issues.tool;

import de.mhus.vance.addon.brain.issues.IssuesApplication;
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

/** Bootstrap an {@code app: issues} folder — manifest + initial refresh. */
@Component
@Slf4j
public class IssueAppCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "Target folder (e.g. 'issues'). _app.yaml is written inside it."));
                put("title", Map.of("type", "string"));
                put("description", Map.of("type", "string"));
                put("overwrite", Map.of("type", "boolean"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final IssuesApplication application;

    public IssueAppCreateTool(EddieContext eddieContext, IssuesApplication application) {
        this.eddieContext = eddieContext;
        this.application = application;
    }

    @Override public String name() { return "issue_app_create"; }

    @Override
    public String description() {
        return "Bootstrap an issue tracker folder (GitHub-Issues-style). Writes the "
                + "_app.yaml manifest (kind: application, app: issues) and generates "
                + "_index.md + _stats.yaml. Add issues afterwards with issue_create.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("eddie", "write", "document", "issues", "application"); }
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
                ctx.tenantId(), project.getName(), folder, ctx.userId(), ctx.processId(), overwrite, appParams);
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
