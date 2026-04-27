package de.mhus.vance.brain.tools.recipe;

import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Detailed view of one recipe — full default-params, prompt-prefix,
 * tool adjustments, source attribution. Secondary because the LLM
 * rarely needs more than {@code recipe_list} returns; pull this only
 * when you want to know exactly what defaults a recipe applies before
 * overriding them.
 */
@Component
@RequiredArgsConstructor
public class RecipeDescribeTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Recipe name to describe.")),
            "required", List.of("name"));

    private final RecipeResolver resolver;

    @Override
    public String name() {
        return "recipe_describe";
    }

    @Override
    public String description() {
        return "Get the full configuration of one recipe — engine, default "
                + "params, prompt-prefix, tool adjustments, source.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object rawName = params == null ? null : params.get("name");
        if (!(rawName instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required");
        }
        Optional<ResolvedRecipe> resolved = resolver.resolve(
                ctx.tenantId(), ctx.projectId(), name);
        if (resolved.isEmpty()) {
            throw new ToolException("Unknown recipe '" + name + "'");
        }
        ResolvedRecipe r = resolved.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", r.name());
        out.put("description", r.description());
        out.put("engine", r.engine());
        out.put("source", r.source().name());
        out.put("params", r.params());
        if (r.promptPrefix() != null) {
            out.put("promptPrefix", r.promptPrefix());
            out.put("promptMode", r.promptMode().name());
        }
        if (!r.allowedToolsAdd().isEmpty()) {
            out.put("allowedToolsAdd", r.allowedToolsAdd());
        }
        if (!r.allowedToolsRemove().isEmpty()) {
            out.put("allowedToolsRemove", r.allowedToolsRemove());
        }
        if (r.locked()) {
            out.put("locked", true);
        }
        if (!r.tags().isEmpty()) {
            out.put("tags", r.tags());
        }
        return out;
    }
}
