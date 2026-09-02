package de.mhus.vance.addon.brain.gtd.tool;

import de.mhus.vance.addon.brain.gtd.GtdAction;
import de.mhus.vance.addon.brain.gtd.GtdBucket;
import de.mhus.vance.addon.brain.gtd.GtdFolderReader;
import de.mhus.vance.addon.brain.gtd.GtdService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * List GTD actions in a derived bucket, optionally filtered by context or
 * project. Deterministic — the bucket is computed from `when` + today.
 */
@Component
@Slf4j
public class GtdQueryTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string", "description", "GTD root folder."));
                put("bucket", Map.of("type", "string",
                        "description", "inbox | today | upcoming | anytime | someday | trash "
                                + "(optional; omitted lists every bucket except trash)."));
                put("includeTrash", Map.of("type", "boolean",
                        "description", "Include the trash bucket in an unfiltered listing. "
                                + "Default false."));
                put("includeDone", Map.of("type", "boolean",
                        "description", "Include completed actions. Default false."));
                put("context", Map.of("type", "string"));
                put("project", Map.of("type", "string"));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final GtdFolderReader folderReader;
    private final GtdService gtdService;

    public GtdQueryTool(EddieContext eddieContext, GtdFolderReader folderReader, GtdService gtdService) {
        this.eddieContext = eddieContext;
        this.folderReader = folderReader;
        this.gtdService = gtdService;
    }

    @Override public String name() { return "gtd_query"; }

    @Override
    public String description() {
        return "List GTD actions by bucket (inbox/today/upcoming/anytime/someday/trash), "
                + "optionally filtered by context or project. Returns title + when + contexts "
                + "per action (no body). Completed actions and the trash are left out unless "
                + "asked for (includeDone / includeTrash / bucket=trash) — a completed action "
                + "stays in its bucket until app_rebuild sweeps it into the trash.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("eddie", "read", "document", "gtd"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        @Nullable GtdBucket wanted = GtdBucket.fromWire(paramString(params, "bucket"));
        String context = paramString(params, "context");
        String project = paramString(params, "project");
        // Two things a plain "what's on my list" must not sweep up: the bin,
        // and things already ticked off. Both are still reachable — by naming
        // them, which is the point.
        boolean includeTrash = paramBoolean(params, "includeTrash");
        boolean includeDone = paramBoolean(params, "includeDone") || wanted == GtdBucket.TRASH;

        ProjectDocument project0 = eddieContext.resolveProject(params, ctx, false);
        GtdFolderReader.Scan scan = folderReader.scan(ctx.tenantId(), project0.getName(), folder);
        Map<GtdBucket, List<GtdAction>> grouped = gtdService.computeBuckets(scan, LocalDate.now());

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<GtdBucket, List<GtdAction>> e : grouped.entrySet()) {
            if (wanted != null && e.getKey() != wanted) continue;
            if (wanted == null && e.getKey() == GtdBucket.TRASH && !includeTrash) continue;
            // Same manual order (§8b) the person sees in the app — a list that
            // disagreed with theirs would make "the top three" mean two things.
            for (GtdAction a : gtdService.applyBucketOrder(
                    e.getKey(), scan.config(), e.getValue())) {
                if (context != null && !a.contexts().contains(context)) continue;
                if (project != null && !project.equals(a.project())) continue;
                if (a.done() && !includeDone) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("bucket", e.getKey().wireName());
                row.put("title", a.title());
                row.put("path", a.doc().getPath());
                if (a.done()) row.put("done", true);
                if (!a.when().isEmpty()) row.put("when", a.when());
                if (a.deadline() != null) row.put("deadline", a.deadline());
                if (!a.contexts().isEmpty()) row.put("contexts", a.contexts());
                if (a.project() != null) row.put("project", a.project());
                out.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", out.size());
        result.put("actions", out);
        return result;
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static boolean paramBoolean(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Boolean b) return b;
        return v instanceof String s && Boolean.parseBoolean(s.trim());
    }
}
