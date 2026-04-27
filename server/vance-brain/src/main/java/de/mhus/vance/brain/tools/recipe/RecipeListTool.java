package de.mhus.vance.brain.tools.recipe;

import de.mhus.vance.brain.recipe.BundledRecipe;
import de.mhus.vance.brain.recipe.BundledRecipeRegistry;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.recipe.RecipeDocument;
import de.mhus.vance.shared.recipe.RecipeService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lists all recipes visible to the current tenant/project, with the
 * cascade applied. Bundled defaults are included; tenant- and
 * project-overrides hide bundled entries with the same name.
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

    private final RecipeService recipeService;
    private final BundledRecipeRegistry bundled;

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
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        // Project-scoped recipes first (highest priority).
        if (ctx.projectId() != null) {
            for (RecipeDocument d : recipeService.listProject(ctx.tenantId(), ctx.projectId())) {
                if (seen.add(d.getName())) {
                    rows.add(rowFromDocument(d, RecipeSource.PROJECT));
                }
            }
        }
        // Tenant-scoped recipes next.
        for (RecipeDocument d : recipeService.listTenant(ctx.tenantId())) {
            if (seen.add(d.getName())) {
                rows.add(rowFromDocument(d, RecipeSource.TENANT));
            }
        }
        // Bundled defaults last — only the names not already covered.
        for (BundledRecipe b : bundled.all()) {
            if (seen.add(b.name())) {
                rows.add(rowFromBundled(b));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recipes", rows);
        out.put("count", rows.size());
        return out;
    }

    private static Map<String, Object> rowFromDocument(RecipeDocument d, RecipeSource source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", d.getName());
        row.put("description", d.getDescription());
        row.put("engine", d.getEngine());
        row.put("source", source.name());
        if (d.getTags() != null && !d.getTags().isEmpty()) {
            row.put("tags", d.getTags());
        }
        if (d.isLocked()) {
            row.put("locked", true);
        }
        return row;
    }

    private static Map<String, Object> rowFromBundled(BundledRecipe b) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", b.name());
        row.put("description", b.description());
        row.put("engine", b.engine());
        row.put("source", RecipeSource.BUNDLED.name());
        if (b.tags() != null && !b.tags().isEmpty()) {
            row.put("tags", b.tags());
        }
        if (b.locked()) {
            row.put("locked", true);
        }
        return row;
    }
}
