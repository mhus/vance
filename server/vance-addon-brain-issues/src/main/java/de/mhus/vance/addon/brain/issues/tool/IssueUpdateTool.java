package de.mhus.vance.addon.brain.issues.tool;

import de.mhus.vance.addon.brain.issues.IssuesConfig;
import de.mhus.vance.addon.brain.issues.IssuesFolderReader;
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

/**
 * Update an issue: state (open/closed), labels, assignee, priority, title, body,
 * and/or archive it. Archiving ({@code archived=true}) moves the file to
 * {@code archive/} (out of the active tracker); {@code archived=false} restores it.
 */
@Component
@Slf4j
public class IssueUpdateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string", "description", "Issues root folder."));
                put("path", Map.of("type", "string", "description", "Full document path of the issue."));
                put("state", Map.of("type", "string", "description", "open | closed"));
                put("labels", Map.of("type", "array", "items", Map.of("type", "string")));
                put("assignee", Map.of("type", "string"));
                put("priority", Map.of("type", "string"));
                put("title", Map.of("type", "string"));
                put("body", Map.of("type", "string"));
                put("archived", Map.of("type", "boolean",
                        "description", "true = move to archive/, false = restore to items/"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder", "path"));

    private final EddieContext eddieContext;
    private final IssuesFolderReader folderReader;
    private final IssuesService issuesService;

    public IssueUpdateTool(EddieContext eddieContext, IssuesFolderReader folderReader, IssuesService issuesService) {
        this.eddieContext = eddieContext;
        this.folderReader = folderReader;
        this.issuesService = issuesService;
    }

    @Override public String name() { return "issue_update"; }

    @Override
    public String description() {
        return "Update an issue in place: set state (open/closed), labels, assignee, "
                + "priority, title or body. Set archived=true to move it out of the active "
                + "tracker into archive/ (archived=false restores it). Run app_rebuild afterwards.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("eddie", "write", "document", "issues"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        String path = paramString(params, "path");
        if (path == null) throw new ToolException("path is required");
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);

        boolean touchedFields = params.containsKey("state") || params.containsKey("labels")
                || params.containsKey("assignee") || params.containsKey("priority")
                || params.containsKey("title") || params.containsKey("body");
        DocumentDocument doc = null;
        if (touchedFields) {
            doc = issuesService.updateIssue(ctx.tenantId(), project.getName(), path,
                    paramString(params, "state"), paramStringList(params, "labels"),
                    paramString(params, "assignee"), paramString(params, "priority"),
                    paramString(params, "title"), paramString(params, "body"));
            path = doc.getPath();
        }
        Boolean archived = paramBoolean(params, "archived");
        if (archived != null) {
            IssuesConfig config = folderReader.scan(ctx.tenantId(), project.getName(), folder).config();
            doc = archived
                    ? issuesService.archive(ctx.tenantId(), project.getName(), folder, config, path)
                    : issuesService.unarchive(ctx.tenantId(), project.getName(), folder, config, path);
        }
        if (doc == null) throw new ToolException("Nothing to update");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", doc.getPath());
        result.put("id", doc.getId());
        return result;
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
    private static @Nullable Boolean paramBoolean(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s);
        return null;
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
