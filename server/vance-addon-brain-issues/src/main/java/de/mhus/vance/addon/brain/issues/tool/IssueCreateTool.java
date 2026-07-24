package de.mhus.vance.addon.brain.issues.tool;

import de.mhus.vance.addon.brain.issues.IssuesService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Create a new issue (open). The number is assigned automatically. */
@Component
@Slf4j
public class IssueCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string", "description", "Issues root folder."));
                put("title", Map.of("type", "string"));
                put("labels", Map.of("type", "array", "items", Map.of("type", "string")));
                put("assignee", Map.of("type", "string"));
                put("priority", Map.of("type", "string"));
                put("body", Map.of("type", "string"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder", "title"));

    private final EddieContext eddieContext;
    private final IssuesService issuesService;

    public IssueCreateTool(EddieContext eddieContext, IssuesService issuesService) {
        this.eddieContext = eddieContext;
        this.issuesService = issuesService;
    }

    @Override public String name() { return "issue_create"; }

    @Override
    public String description() {
        return "Create a new issue (state open). The stable number (#N) is assigned "
                + "automatically. Add labels, assignee, priority and a Markdown body. "
                + "Run app_rebuild('folder') afterwards to refresh the index + stats.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("eddie", "write", "document", "issues"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        String title = paramString(params, "title");
        if (title == null) throw new ToolException("title is required");
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        DocumentDocument doc = issuesService.createIssue(ctx.tenantId(), project.getName(), folder,
                title, paramStringList(params, "labels"), paramString(params, "assignee"),
                paramString(params, "priority"), paramString(params, "body"), ctx.userId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", doc.getPath());
        result.put("id", doc.getId());
        return result;
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
    private static @Nullable List<String> paramStringList(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
            return out;
        }
        if (v instanceof String s && !s.isBlank()) {
            List<String> out = new ArrayList<>();
            for (String part : s.split(",")) if (!part.isBlank()) out.add(part.trim());
            return out;
        }
        return null;
    }
}
