package de.mhus.vance.brain.tools.manual;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Lists Markdown manuals visible to the running process. The folder
 * list comes from {@code params.manualPaths} on the process — the
 * recipe author (and per-profile overrides) decides which folders are
 * unioned. Each folder is resolved through the document cascade
 * (project → {@code _vance} → classpath:vance-defaults), and the
 * results are merged across folders with first-wins precedence in
 * recipe order.
 *
 * <p>Paired with {@link ManualReadTool} which fetches a specific
 * manual by name.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ManualListTool implements Tool {

    static final String MD_SUFFIX = ".md";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final DocumentService documentService;
    private final ThinkProcessService thinkProcessService;

    @Override
    public String name() {
        return "manual_list";
    }

    @Override
    public String description() {
        return "List Markdown manuals describing how Vance works in the "
                + "current scope — recipe-configured folder list "
                + "(see params.manualPaths). Read one with "
                + "manual_read(name). Consult these whenever the user "
                + "asks about Vance itself or you need to operate a "
                + "feature you are unsure about.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException("manual_list requires a tenant scope");
        }
        List<String> folders = ManualPaths.readFor(ctx, thinkProcessService);
        if (folders.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("manuals", List.of());
            empty.put("count", 0);
            empty.put("note", "No manualPaths configured in the recipe.");
            return empty;
        }

        // First-wins per stem across folders → recipe order is precedence.
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String folder : folders) {
            Map<String, LookupResult> hits = documentService.listByPrefixCascade(
                    ctx.tenantId(), ctx.projectId(), folder);
            for (Map.Entry<String, LookupResult> e : hits.entrySet()) {
                String path = e.getKey();
                if (!path.endsWith(MD_SUFFIX)) continue;
                String filename = path.substring(folder.length());
                String stem = filename.substring(0, filename.length() - MD_SUFFIX.length());
                if (stem.isBlank()) continue;
                if (!seen.add(stem)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", stem);
                row.put("folder", folder);
                row.put("source", e.getValue().source().name().toLowerCase());
                rows.add(row);
            }
        }
        rows.sort((a, b) -> ((String) a.get("name")).compareTo((String) b.get("name")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("manuals", rows);
        out.put("count", rows.size());
        return out;
    }
}
