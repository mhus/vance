package de.mhus.vance.addon.brain.issues.tool;

import de.mhus.vance.addon.brain.issues.IssuesFolderReader;
import de.mhus.vance.addon.brain.issues.IssuesService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentService;
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

/** Free-text search over issues (title / summary / labels). */
@Component
@Slf4j
public class IssueSearchTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string", "description", "Issues root folder."));
                put("query", Map.of("type", "string"));
                put("label", Map.of("type", "string"));
                put("limit", Map.of("type", "integer", "description", "Max hits (default 20)."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final IssuesFolderReader folderReader;
    private final IssuesService issuesService;

    public IssueSearchTool(EddieContext eddieContext, IssuesFolderReader folderReader, IssuesService issuesService) {
        this.eddieContext = eddieContext;
        this.folderReader = folderReader;
        this.issuesService = issuesService;
    }

    @Override public String name() { return "issue_search"; }

    @Override
    public String description() {
        return "Search issues by free text (title / summary / labels), optionally filtered "
                + "by a label. The body is not directly searchable — hits rank on the summary. "
                + "Returns number-bearing title + snippet per hit.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("eddie", "read", "document", "issues", "search"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        int limit = paramInt(params, "limit", 20);
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        folderReader.scan(ctx.tenantId(), project.getName(), folder); // validates it's an issues app
        DocumentService.DocumentMetaListing listing = issuesService.search(
                ctx.tenantId(), project.getName(), folder,
                paramString(params, "query"), paramString(params, "label"), limit);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (DocumentService.DocumentMetaMatch m : listing.items()) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("title", m.title());
            h.put("path", m.path());
            h.put("snippet", m.snippet());
            hits.add(h);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", listing.total());
        result.put("hits", hits);
        return result;
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
    private static int paramInt(Map<String, Object> params, String key, int fallback) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) { try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { /* keep */ } }
        return fallback;
    }
}
