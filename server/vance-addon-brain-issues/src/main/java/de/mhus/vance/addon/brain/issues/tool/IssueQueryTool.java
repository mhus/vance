package de.mhus.vance.addon.brain.issues.tool;

import de.mhus.vance.addon.brain.issues.Issue;
import de.mhus.vance.addon.brain.issues.IssuesConfig;
import de.mhus.vance.addon.brain.issues.IssuesFolderReader;
import de.mhus.vance.brain.tools.eddie.EddieContext;
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

/** List issues by state / label / assignee (active or archived). */
@Component
@Slf4j
public class IssueQueryTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string", "description", "Issues root folder."));
                put("state", Map.of("type", "string", "description", "open | closed (optional)."));
                put("label", Map.of("type", "string"));
                put("assignee", Map.of("type", "string"));
                put("archived", Map.of("type", "boolean", "description", "List archived issues instead."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final IssuesFolderReader folderReader;

    public IssueQueryTool(EddieContext eddieContext, IssuesFolderReader folderReader) {
        this.eddieContext = eddieContext;
        this.folderReader = folderReader;
    }

    @Override public String name() { return "issue_query"; }

    @Override
    public String description() {
        return "List issues by state (open/closed), label or assignee. Set archived=true "
                + "to list archived issues. Returns number + title + state + labels per issue.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("eddie", "read", "document", "issues"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        String state = paramString(params, "state");
        String label = paramString(params, "label");
        String assignee = paramString(params, "assignee");
        boolean archived = paramBoolean(params, "archived");
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        IssuesFolderReader.Scan scan = folderReader.scan(ctx.tenantId(), project.getName(), folder);
        IssuesConfig config = scan.config();
        List<Issue> source = archived
                ? folderReader.scanArchived(ctx.tenantId(), project.getName(), folder, config)
                : scan.issues();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Issue i : source) {
            if (state != null && !state.equalsIgnoreCase(i.state())) continue;
            if (label != null && !i.labels().contains(label)) continue;
            if (assignee != null && !assignee.equals(i.assignee())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("number", i.number());
            row.put("title", i.title());
            row.put("state", i.state());
            row.put("path", i.doc().getPath());
            if (!i.labels().isEmpty()) row.put("labels", i.labels());
            if (i.assignee() != null) row.put("assignee", i.assignee());
            out.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", out.size());
        result.put("issues", out);
        return result;
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
