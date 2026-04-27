package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.thinkprocess.PromptMode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads the bundled recipe catalog from the classpath
 * {@code recipes.yaml} at construction time. Fail-fast: if the file
 * is missing or malformed the brain refuses to start, since recipes
 * are a hard dependency for Arthur's default behaviour.
 *
 * <p>The registry is in-memory and immutable after boot — YAML edits
 * require a brain restart. Tenant- and project-level overrides live
 * in MongoDB and are consulted by {@code RecipeResolver} <em>before</em>
 * this registry.
 *
 * <p>YAML schema (see {@code recipes.yaml}):
 * <pre>{@code
 * recipes:
 *   - name: analyze
 *     description: ...
 *     engine: zaphod
 *     params:
 *       model: claude-sonnet-4-5
 *     promptPrefix: ...
 *     promptMode: APPEND
 *     allowedToolsAdd: []
 *     allowedToolsRemove: []
 *     locked: false
 *     tags: [analysis, research]
 * }</pre>
 */
@Service
@Slf4j
public class BundledRecipeRegistry {

    private static final String RESOURCE = "recipes.yaml";

    private final Map<String, BundledRecipe> byName = new LinkedHashMap<>();
    private final List<BundledRecipe> ordered = new ArrayList<>();

    public BundledRecipeRegistry() {
        load();
    }

    /** Lookup a bundled recipe by name. Lowercase comparison. */
    public Optional<BundledRecipe> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(byName.get(name.toLowerCase().trim()));
    }

    /** All bundled recipes in declaration order. Defensive copy. */
    public List<BundledRecipe> all() {
        return List.copyOf(ordered);
    }

    public int size() {
        return ordered.size();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Bundled recipes resource '" + RESOURCE
                            + "' not found on classpath — brain cannot start");
        }
        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try (InputStream in = resource.getInputStream()) {
            Object parsed = yaml.load(in);
            if (!(parsed instanceof Map<?, ?> m)) {
                throw new IllegalStateException(
                        "Bundled recipes file '" + RESOURCE
                                + "' must have a YAML map at the top level");
            }
            root = (Map<String, Object>) m;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read bundled recipes '" + RESOURCE + "'", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Failed to parse bundled recipes '" + RESOURCE + "': " + e.getMessage(), e);
        }

        Object recipesObj = root.get("recipes");
        if (!(recipesObj instanceof List<?> recipesList)) {
            throw new IllegalStateException(
                    "Bundled recipes file '" + RESOURCE
                            + "' must contain a top-level 'recipes:' list");
        }
        for (int i = 0; i < recipesList.size(); i++) {
            Object entry = recipesList.get(i);
            if (!(entry instanceof Map<?, ?> spec)) {
                throw new IllegalStateException(
                        "Bundled recipe at index " + i
                                + " is not a map (file: '" + RESOURCE + "')");
            }
            BundledRecipe recipe = parseOne((Map<String, Object>) spec, i);
            String key = recipe.name().toLowerCase().trim();
            if (byName.containsKey(key)) {
                throw new IllegalStateException(
                        "Duplicate bundled recipe name '" + recipe.name()
                                + "' (file: '" + RESOURCE + "')");
            }
            byName.put(key, recipe);
            ordered.add(recipe);
        }
        log.info("BundledRecipeRegistry: loaded {} recipes from '{}': {}",
                ordered.size(), RESOURCE,
                ordered.stream().map(BundledRecipe::name).toList());
    }

    @SuppressWarnings("unchecked")
    private static BundledRecipe parseOne(Map<String, Object> spec, int index) {
        String name = requireNonBlankString(spec, "name", index);
        String description = requireNonBlankString(spec, "description", index);
        String engine = requireNonBlankString(spec, "engine", index);

        Map<String, Object> params = new LinkedHashMap<>();
        Object rawParams = spec.get("params");
        if (rawParams != null) {
            if (!(rawParams instanceof Map<?, ?> pm)) {
                throw new IllegalStateException(
                        "Recipe '" + name + "': 'params' must be a map");
            }
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                params.put(String.valueOf(e.getKey()), e.getValue());
            }
        }

        String promptPrefix = stringOrNull(spec.get("promptPrefix"));
        PromptMode promptMode = parsePromptMode(spec.get("promptMode"), name);

        List<String> add = stringList(spec.get("allowedToolsAdd"), name, "allowedToolsAdd");
        List<String> remove = stringList(spec.get("allowedToolsRemove"), name, "allowedToolsRemove");

        boolean locked = spec.get("locked") instanceof Boolean b && b;
        List<String> tags = stringList(spec.get("tags"), name, "tags");

        return new BundledRecipe(
                name, description, engine, params,
                promptPrefix, promptMode, add, remove, locked, tags);
    }

    private static String requireNonBlankString(Map<String, Object> spec, String key, int index) {
        Object raw = spec.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(
                    "Recipe at index " + index + " is missing required string '" + key + "'");
        }
        return s;
    }

    private static String stringOrNull(Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static PromptMode parsePromptMode(Object raw, String recipeName) {
        if (raw == null) return PromptMode.APPEND;
        if (!(raw instanceof String s)) {
            throw new IllegalStateException(
                    "Recipe '" + recipeName + "': promptMode must be a string");
        }
        try {
            return PromptMode.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Recipe '" + recipeName + "': unknown promptMode '" + s
                            + "' — expected APPEND or OVERWRITE");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object raw, String recipeName, String fieldName) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(
                    "Recipe '" + recipeName + "': '" + fieldName + "' must be a list");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalStateException(
                        "Recipe '" + recipeName + "': '" + fieldName
                                + "' contains a non-string or blank entry");
            }
            out.add(s);
        }
        return List.copyOf(out);
    }
}
