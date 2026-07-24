package de.mhus.vance.addon.brain.issues.tool;

import de.mhus.vance.addon.brain.issues.IssuesService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentNote;
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

/** Add a comment to an issue's discussion thread (backed by a DocumentNote). */
@Component
@Slf4j
public class IssueCommentTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("path", Map.of("type", "string", "description", "Full document path of the issue."));
                put("text", Map.of("type", "string"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("path", "text"));

    private final EddieContext eddieContext;
    private final IssuesService issuesService;

    public IssueCommentTool(EddieContext eddieContext, IssuesService issuesService) {
        this.eddieContext = eddieContext;
        this.issuesService = issuesService;
    }

    @Override public String name() { return "issue_comment"; }

    @Override
    public String description() {
        return "Add a comment to an issue's discussion thread. Comments are stored as "
                + "document notes on the issue (atomic, no full rewrite).";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("eddie", "write", "document", "issues"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = paramString(params, "path");
        if (path == null) throw new ToolException("path is required");
        String text = paramString(params, "text");
        if (text == null) throw new ToolException("text is required");
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        DocumentNote note = issuesService.addComment(ctx.tenantId(), project.getName(), path, text, ctx.userId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commentId", note.getId());
        result.put("path", path);
        return result;
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
