package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.api.kit.ProjectKitEntry;
import de.mhus.vance.api.kit.ProjectKitsCatalogDto;
import de.mhus.vance.shared.kit.catalog.ProjectKitsCatalogService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Lists the tenant-wide project-kits catalog so Eddie can offer a
 * sensible kit name to {@code project_create}. The tool is read-only
 * and returns just {@code name}, {@code title} and {@code description}
 * — kit-internal source URLs are stripped because the LLM doesn't need
 * them and they would pollute the context window.
 *
 * <p>Spec: {@code specification/project-kits-catalog.md} §4.3.
 */
@Component
@RequiredArgsConstructor
public class KitListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final ProjectKitsCatalogService catalogService;

    @Override
    public String name() {
        return "kit_list";
    }

    @Override
    public String description() {
        return "List the project-kits available in this tenant. Each entry "
                + "has a name (the catalog key to pass to project_create as "
                + "kitName), a title and an optional description. The list "
                + "is curated tenant-side; offer the user one of these "
                + "instead of guessing kit URLs.";
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
    public java.util.Set<String> labels() {
        return java.util.Set.of("eddie", "read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ProjectKitsCatalogDto catalog = catalogService.load(ctx.tenantId());
        List<Map<String, Object>> rows = new ArrayList<>();
        if (catalog.getKits() != null) {
            for (ProjectKitEntry entry : catalog.getKits()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", entry.getName());
                row.put("title", entry.getTitle());
                if (!StringUtils.isBlank(entry.getDescription())) {
                    row.put("description", entry.getDescription());
                }
                rows.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kits", rows);
        out.put("count", rows.size());
        return out;
    }
}
