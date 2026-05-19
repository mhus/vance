package de.mhus.vance.brain.tools.template;

import de.mhus.vance.api.kit.ToolTemplateCatalogDto;
import de.mhus.vance.api.kit.ToolTemplateCatalogEntry;
import de.mhus.vance.shared.kit.catalog.ToolTemplateCatalogService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Lists tool-templates the tenant has curated in
 * {@code _tenant/config/tool-templates.yaml}. Read-only counterpart of
 * {@code project_create}'s {@code kit_list}, scoped to additive tool
 * configurations (Jira, IMAP, SMTP, …) rather than whole-project kits.
 *
 * <p>The agent typically calls this first when the user says "set up
 * X" — get the candidate name, then drill into {@code tool_template_describe}
 * for the input schema.
 */
@Component
@RequiredArgsConstructor
public class ToolTemplateListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final ToolTemplateCatalogService catalogService;

    @Override public String name() { return "tool_template_list"; }

    @Override public String description() {
        return "List the tool-templates curated for this tenant (Jira, IMAP, "
                + "SMTP, …). Returns each entry's name (the lookup key for "
                + "tool_template_describe / tool_template_apply), title, "
                + "description and category. The list is curated by the "
                + "tenant admin — offer the user from these instead of "
                + "guessing kit URLs.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("tool-template", "read-only"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ToolTemplateCatalogDto catalog = catalogService.load(ctx.tenantId());
        List<Map<String, Object>> rows = new ArrayList<>();
        if (catalog.getTemplates() != null) {
            for (ToolTemplateCatalogEntry e : catalog.getTemplates()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", e.getName());
                if (!StringUtils.isBlank(e.getTitle())) row.put("title", e.getTitle());
                if (!StringUtils.isBlank(e.getDescription())) row.put("description", e.getDescription());
                if (!StringUtils.isBlank(e.getCategory())) row.put("category", e.getCategory());
                rows.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templates", rows);
        out.put("count", rows.size());
        return out;
    }
}
