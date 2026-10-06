package de.mhus.vance.brain.recipe;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * {@link KindHandler} for the {@code vance-recipe} kind — one named
 * configuration bundle of the recipe system
 * ({@code _vance/recipes/<name>.yaml}, spec
 * {@code specification/public/recipes.md}). The Slart architect's working
 * trees ({@code _vance/recipes/_user/}, {@code _vance/recipes/_slart/}) are
 * recipe-shaped documents under the same prefix and are claimed with it.
 *
 * <p><b>Location, not body, is the marker</b> — the loader resolves by name
 * from exactly that folder, same rule as the source-config kinds. A body
 * detector would claim nothing useful: the recipe body is its own shape.
 *
 * <p><b>The findings mean what {@link RecipeLoader#load} does to a broken
 * recipe: it is silently missing.</b> The loader logs WARN and returns empty,
 * so a recipe with a syntax error in its {@code promptPrefix} does not fail
 * loudly anywhere — it just never spawns. These checks are deliberately the
 * thin slice the operator can actually get wrong by hand (required fields,
 * known engine, Pebble-compilable prompt); the deep validation — profiles,
 * modes, guards, params shadowing — stays with the loader, which is the one
 * place that parses recipes for every caller.
 */
@Service
public class RecipeDocKindHandler implements KindHandler {

    public static final String KIND = "vance-recipe";

    private final PromptTemplateRenderer templateRenderer;
    private final Set<String> engineNames;

    public RecipeDocKindHandler(PromptTemplateRenderer templateRenderer, List<ThinkEngine> engines) {
        this.templateRenderer = templateRenderer;
        this.engineNames = engines.stream().map(ThinkEngine::name).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String getName() {
        return KIND;
    }

    /**
     * The recipe folder is the marker: every document under
     * {@code _vance/recipes/} is a recipe definition — including the Slart
     * architect's working trees, which hold recipe-shaped documents.
     */
    @Override
    public boolean detectsPath(String documentPath) {
        return documentPath.startsWith(RecipeLoader.RECIPE_PATH_PREFIX);
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? KIND : ctx.docPath();
        Object parsed;
        try {
            parsed = new Yaml().load(content);
        } catch (RuntimeException e) {
            return List.of(Finding.error(target, "recipe-parse", e.getMessage()));
        }
        if (parsed == null || StringUtils.isBlank(content)) {
            return List.of(Finding.error(
                    target,
                    "recipe-empty",
                    "an empty document is not a recipe — the loader needs description and engine"));
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            return List.of(Finding.error(target, "recipe-mapping", "a recipe document must be a YAML mapping"));
        }

        List<Finding> findings = new java.util.ArrayList<>();
        if (StringUtils.isBlank(stringOf(map.get("description")))) {
            findings.add(Finding.error(
                    target,
                    "recipe-description",
                    "missing required field 'description' — the loader refuses the recipe"));
        }
        String engine = stringOf(map.get("engine"));
        if (StringUtils.isBlank(engine)) {
            findings.add(Finding.error(
                    target, "recipe-engine", "missing required field 'engine' — the loader refuses the recipe"));
        } else if (!engineNames.contains(engine)) {
            findings.add(Finding.error(
                    target,
                    "recipe-engine-unknown",
                    "unknown engine '" + engine + "' — this deployment serves: " + new TreeSet<>(engineNames)));
        }
        Object params = map.get("params");
        if (params != null && !(params instanceof Map)) {
            findings.add(Finding.error(target, "recipe-params", "'params' must be a map"));
        }
        String promptPrefix = stringOf(map.get("promptPrefix"));
        if (promptPrefix != null && !promptPrefix.isBlank()) {
            try {
                templateRenderer.compile(promptPrefix);
            } catch (RuntimeException e) {
                findings.add(Finding.error(
                        target, "recipe-prompt-prefix", "'promptPrefix' is not a valid template: " + e.getMessage()));
            }
        }
        return findings;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
