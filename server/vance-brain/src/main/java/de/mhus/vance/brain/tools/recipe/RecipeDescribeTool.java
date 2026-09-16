package de.mhus.vance.brain.tools.recipe;

import de.mhus.vance.brain.recipe.RecipeProjectKind;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Detailed view of one recipe — <b>every</b> parsed field, so a caller
 * can reconstruct the recipe file. That matters when writing a
 * project-level override (e.g. adding {@code webTheme:}): the
 * recipe cascade is first-hit-wins with no merge, so the override
 * must carry every field the original had — anything this tool does
 * not show would be silently lost. Secondary because the LLM rarely
 * needs more than {@code recipe_list} returns; pull this only when
 * you want to know exactly what a recipe applies, or before writing
 * an override.
 */
@Component
@RequiredArgsConstructor
public class RecipeDescribeTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "name",
                            Map.of(
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
        return "Get the complete configuration of one recipe — every parsed"
                + " field (engine, params, prompts, tool adjustments, guards, modes,"
                + " profiles, metadata, source), so an override can carry them all.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean contributesPrak() {
        // Recipe metadata — config snapshot, not durable insight.
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("read-only");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object rawName = params == null ? null : params.get("name");
        if (!(rawName instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required");
        }
        Optional<ResolvedRecipe> resolved = resolver.resolve(ctx.tenantId(), ctx.projectId(), name);
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
        if (!r.allowedToolsDefer().isEmpty()) {
            out.put("allowedToolsDefer", r.allowedToolsDefer());
        }
        if (!r.allowedToolsKeep().isEmpty()) {
            out.put("allowedToolsKeep", r.allowedToolsKeep());
        }
        if (!r.allowedToolsDropFirst().isEmpty()) {
            out.put("allowedToolsDropFirst", r.allowedToolsDropFirst());
        }
        if (!r.modes().isEmpty()) {
            out.put("modes", r.modes());
        }
        if (!r.profiles().isEmpty()) {
            out.put("profiles", r.profiles());
        }
        if (!r.defaultActiveSkills().isEmpty()) {
            out.put("defaultActiveSkills", r.defaultActiveSkills());
        }
        if (r.allowedSkills() != null) {
            out.put("allowedSkills", r.allowedSkills());
        }
        if (!r.triggerKeywords().isEmpty()) {
            out.put("triggerKeywords", r.triggerKeywords());
        }
        if (!r.guards().isEmpty()) {
            out.put("guards", r.guards());
        }
        if (!r.tenants().isEmpty()) {
            out.put("tenants", r.tenants());
        }
        if (r.dataRelayCorrection() != null) {
            out.put("dataRelayCorrection", r.dataRelayCorrection());
        }
        // Metadata / picker surface — display-only fields, but a
        // project-level override that omits them loses them (no merge
        // between cascade layers), so they belong in the complete view.
        if (r.title() != null) {
            out.put("title", r.title());
        }
        if (r.category() != null) {
            out.put("category", r.category());
        }
        if (r.webTheme() != null) {
            out.put("webTheme", r.webTheme());
        }
        if (r.projectKind() != RecipeProjectKind.NORMAL) {
            out.put("projectKind", r.projectKind().name());
        }
        if (r.internal()) {
            out.put("internal", true);
        }
        if (r.listed()) {
            out.put("listed", true);
        }
        if (r.web()) {
            out.put("web", true);
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
