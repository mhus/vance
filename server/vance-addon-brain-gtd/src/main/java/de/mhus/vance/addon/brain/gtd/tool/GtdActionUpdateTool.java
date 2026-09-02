package de.mhus.vance.addon.brain.gtd.tool;

import de.mhus.vance.addon.brain.gtd.GtdBucket;
import de.mhus.vance.addon.brain.gtd.GtdConfig;
import de.mhus.vance.addon.brain.gtd.GtdService;
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
 * Patch a GTD action in place. Set `when` to change its bucket (the core GTD
 * move — Today/Anytime/Someday/Upcoming all live in the `when` attribute),
 * toggle `done`, or edit contexts/deadline/title/body. Setting `project`
 * additionally re-files the action between {@code projects/<name>/} and
 * {@code actions/} — the one field here that moves the file.
 */
@Component
@Slf4j
public class GtdActionUpdateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string", "description", "GTD root folder."));
                put("path", Map.of("type", "string", "description", "Full document path of the action."));
                put("when", Map.of("type", "string",
                        "description", "'' (Anytime) | today | someday | ISO date. Sets the bucket."));
                put("bucket", Map.of("type", "string",
                        "description", "inbox | trash | today | anytime | someday. The only way "
                                + "to reach the two folder buckets: inbox (unprocessed) and "
                                + "trash (put away). Mutually exclusive with `when` — pass one "
                                + "or the other; for Upcoming pass when=<yyyy-MM-dd>. `trash` "
                                + "leaves `when` alone and remembers the folder, so moving the "
                                + "action back out restores it there."));
                put("deadline", Map.of("type", "string"));
                put("contexts", Map.of("type", "array", "items", Map.of("type", "string")));
                put("done", Map.of("type", "boolean"));
                put("title", Map.of("type", "string"));
                put("body", Map.of("type", "string"));
                put("project", Map.of("type", "string",
                        "description", "Re-file into projects/<name>/. Pass \"\" to file it "
                                + "back out into actions/. Omit to leave the folder alone."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder", "path"));

    private final EddieContext eddieContext;
    private final GtdService gtdService;

    public GtdActionUpdateTool(EddieContext eddieContext, GtdService gtdService) {
        this.eddieContext = eddieContext;
        this.gtdService = gtdService;
    }

    @Override public String name() { return "gtd_action_update"; }

    @Override
    public String description() {
        return "Update a GTD action in place. Change its bucket by setting `when` "
                + "('' = Anytime, today, someday, or an ISO date). Mark it complete with "
                + "done=true — that only sets the flag: the action stays in its bucket until "
                + "app_rebuild sweeps every completed action into the trash. Use `bucket` for "
                + "the two buckets that are folders (inbox, trash); moving an action out of "
                + "the trash restores the folder it came from. Also edits contexts, deadline, "
                + "title, body. Set `project` to re-file it into projects/<name>/ (\"\" moves it "
                + "back out into actions/) — that relocates the file and leaves the bucket "
                + "alone. Run app_rebuild afterwards to refresh the views.";
    }

    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("eddie", "write", "document", "gtd"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = paramString(params, "path");
        if (path == null) throw new ToolException("path is required");
        String bucketName = paramString(params, "bucket");
        if (bucketName != null && paramString(params, "when") != null) {
            throw new ToolException("Pass either `when` or `bucket`, not both — "
                    + "both decide the bucket, and there is no rule for which wins.");
        }
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        DocumentDocument doc = gtdService.updateAction(ctx.tenantId(), project.getName(), path,
                paramString(params, "when"), paramString(params, "deadline"),
                paramStringList(params, "contexts"), paramBoolean(params, "done"),
                paramString(params, "title"), paramString(params, "body"));
        if (bucketName != null) {
            GtdBucket bucket = GtdBucket.fromWire(bucketName);
            if (bucket == null || bucket == GtdBucket.UPCOMING) {
                throw new ToolException("Unknown bucket '" + bucketName
                        + "' — use inbox | trash | today | anytime | someday, "
                        + "or when=<yyyy-MM-dd> for Upcoming.");
            }
            String folder = paramString(params, "folder");
            if (folder == null) throw new ToolException("folder is required");
            GtdConfig config = gtdService.scan(ctx.tenantId(), project.getName(), folder).config();
            doc = gtdService.move(ctx.tenantId(), project.getName(), folder, config,
                    doc.getPath(), bucket, null, ctx.userId());
        }
        // Re-filing is a relocation, not a field patch — and here absent has to
        // mean something different from empty: omitting `project` leaves the
        // action's folder alone, "" files it back out into actions/.
        if (params != null && params.containsKey("project")) {
            String folder = paramString(params, "folder");
            if (folder == null) throw new ToolException("folder is required");
            Object raw = params.get("project");
            GtdConfig config = gtdService.scan(ctx.tenantId(), project.getName(), folder).config();
            doc = gtdService.assignProject(ctx.tenantId(), project.getName(), folder, config,
                    doc.getPath(), raw == null ? "" : raw.toString().trim(), ctx.userId());
        }
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
