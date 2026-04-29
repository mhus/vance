package de.mhus.vance.brain.tools.recipe;

import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lists all recipes visible to the current tenant/project, with the
 * cascade applied. Inner layers (project) shadow outer ones
 * ({@code _vance}, classpath) by recipe name.
 *
 * <p>Primary so Arthur sees it on every turn — the LLM can decide to
 * fetch the catalog when it's unsure which recipe applies.
 */
@Component
@RequiredArgsConstructor
public class RecipeListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final RecipeLoader loader;

    @Override
    public String name() {
        return "recipe_list";
    }

    @Override
    public String description() {
        return "List all worker recipes available in this scope. Use the "
                + "result to pick a `recipe` for `process_create`.";
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
        if (ctx.tenantId() == null) {
            throw new ToolException("recipe_list requires a tenant scope");
        }
        String projectId = ctx.projectId() == null || ctx.projectId().isBlank()
                ? HomeBootstrapService.VANCE_PROJECT_NAME
                : ctx.projectId();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ResolvedRecipe r : loader.listAll(ctx.tenantId(), projectId)) {
            rows.add(rowFor(r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recipes", rows);
        out.put("count", rows.size());
        return out;
    }

    private static Map<String, Object> rowFor(ResolvedRecipe r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", r.name());
        row.put("description", r.description());
        row.put("engine", r.engine());
        row.put("source", r.source().name());
        if (r.tags() != null && !r.tags().isEmpty()) {
            row.put("tags", r.tags());
        }
        if (r.locked()) {
            row.put("locked", true);
        }
        return row;
    }
}
