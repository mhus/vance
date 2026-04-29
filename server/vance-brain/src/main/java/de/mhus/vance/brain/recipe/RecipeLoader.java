package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Cascade-aware recipe loader. Reads one YAML per recipe through
 * {@link DocumentService#lookupCascade}: {@code project → _vance →
 * classpath:vance-defaults/recipes/<name>.yaml}. Replaces the previous
 * legacy {@code BundledRecipeRegistry} + {@code RecipeService} split
 * — every tier is now a document under the {@code recipes/} prefix.
 *
 * <p>Recipes are parsed on every read. With 10–100 recipes per tenant
 * and recipes living in the cascade hot path, the cost is dominated by
 * Mongo I/O — YAML parse is negligible. A simple in-memory cache can
 * be added later if profiling requires it.
 *
 * <p>Bundled defaults are <b>not</b> loaded into a registry at boot;
 * the classpath resource layer of {@link DocumentService#lookupCascade}
 * already returns them transparently. The brain therefore starts even
 * if the bundled YAML files are temporarily broken — the failure
 * surfaces only on first lookup, the same way as for tenant edits.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeLoader {

    /** Path prefix used for recipe documents in any cascade tier. */
    public static final String RECIPE_PATH_PREFIX = "recipes/";

    /** File suffix kept on the document path; the recipe name itself does not carry it. */
    public static final String RECIPE_PATH_SUFFIX = ".yaml";

    private final DocumentService documentService;

    /**
     * Resolve {@code name} in the project/_vance/classpath cascade.
     * Returns empty if no tier carries the recipe.
     *
     * @throws RecipeParseException when the YAML at the matched layer
     *         is malformed or missing required fields
     */
    public Optional<ResolvedRecipe> load(
            String tenantId, @Nullable String projectId, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String path = pathFor(name);
        Optional<LookupResult> hit = documentService.lookupCascade(
                tenantId, effectiveProjectId(projectId), path);
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        LookupResult result = hit.get();
        try {
            return Optional.of(parse(name.toLowerCase().trim(), result));
        } catch (RuntimeException e) {
            throw new RecipeParseException(
                    "Failed to parse recipe '" + name + "' from "
                            + result.source() + " at path '" + result.path()
                            + "': " + e.getMessage(), e);
        }
    }

    /**
     * Merged listing across the cascade. Inner layers replace outer
     * ones <b>by recipe name</b>; deactivated documents (status
     * filtered by {@link DocumentService#listByPrefixCascade}) are
     * already excluded by the document layer.
     *
     * <p>Parse errors on individual entries are logged and skipped —
     * a single broken recipe must not poison {@code recipe_list}.
     */
    public List<ResolvedRecipe> listAll(String tenantId, @Nullable String projectId) {
        Map<String, LookupResult> hits = documentService.listByPrefixCascade(
                tenantId, effectiveProjectId(projectId), RECIPE_PATH_PREFIX);
        List<ResolvedRecipe> out = new ArrayList<>(hits.size());
        for (Map.Entry<String, LookupResult> e : hits.entrySet()) {
            String path = e.getKey();
            String name = nameFromPath(path);
            if (name == null) continue;
            try {
                out.add(parse(name, e.getValue()));
            } catch (RuntimeException ex) {
                log.warn("RecipeLoader: skipping malformed recipe path='{}' source={}: {}",
                        path, e.getValue().source(), ex.getMessage());
            }
        }
        return out;
    }

    private static String pathFor(String name) {
        return RECIPE_PATH_PREFIX + name.toLowerCase().trim() + RECIPE_PATH_SUFFIX;
    }

    private static String effectiveProjectId(@Nullable String projectId) {
        return (projectId == null || projectId.isBlank())
                ? HomeBootstrapService.VANCE_PROJECT_NAME : projectId;
    }

    private static String nameFromPath(String path) {
        if (!path.startsWith(RECIPE_PATH_PREFIX)) return null;
        if (!path.endsWith(RECIPE_PATH_SUFFIX)) return null;
        String stem = path.substring(
                RECIPE_PATH_PREFIX.length(),
                path.length() - RECIPE_PATH_SUFFIX.length());
        return stem.isBlank() ? null : stem;
    }

    @SuppressWarnings("unchecked")
    private static ResolvedRecipe parse(String name, LookupResult hit) {
        Yaml yaml = new Yaml();
        Object parsed = yaml.load(hit.content());
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("recipe YAML must have a top-level map");
        }
        Map<String, Object> spec = (Map<String, Object>) rawMap;

        String description = stringOrNull(spec.get("description"));
        if (description == null) {
            throw new IllegalStateException("missing required field 'description'");
        }
        String engine = stringOrNull(spec.get("engine"));
        if (engine == null) {
            throw new IllegalStateException("missing required field 'engine'");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        Object rawParams = spec.get("params");
        if (rawParams != null) {
            if (!(rawParams instanceof Map<?, ?> pm)) {
                throw new IllegalStateException("'params' must be a map");
            }
            for (Map.Entry<?, ?> p : pm.entrySet()) {
                params.put(String.valueOf(p.getKey()), p.getValue());
            }
        }

        String promptPrefix = stringOrNull(spec.get("promptPrefix"));
        String promptPrefixSmall = stringOrNull(spec.get("promptPrefixSmall"));
        PromptMode promptMode = parsePromptMode(spec.get("promptMode"));
        String intentCorrection = stringOrNull(spec.get("intentCorrection"));
        String dataRelayCorrection = stringOrNull(spec.get("dataRelayCorrection"));
        List<String> add = stringList(spec.get("allowedToolsAdd"), "allowedToolsAdd");
        List<String> remove = stringList(spec.get("allowedToolsRemove"), "allowedToolsRemove");
        boolean locked = spec.get("locked") instanceof Boolean b && b;
        List<String> tags = stringList(spec.get("tags"), "tags");

        return new ResolvedRecipe(
                name, description, engine, params,
                promptPrefix, promptPrefixSmall, promptMode,
                intentCorrection, dataRelayCorrection,
                add, remove, locked, tags,
                mapSource(hit.source()));
    }

    private static RecipeSource mapSource(LookupResult.Source source) {
        return switch (source) {
            case PROJECT -> RecipeSource.PROJECT;
            case VANCE -> RecipeSource.VANCE;
            case RESOURCE -> RecipeSource.RESOURCE;
        };
    }

    private static String stringOrNull(Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static PromptMode parsePromptMode(Object raw) {
        if (raw == null) return PromptMode.APPEND;
        if (!(raw instanceof String s)) {
            throw new IllegalStateException("'promptMode' must be a string");
        }
        try {
            return PromptMode.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "unknown promptMode '" + s + "' — expected APPEND or OVERWRITE");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object raw, String fieldName) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'" + fieldName + "' must be a list");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalStateException(
                        "'" + fieldName + "' contains a non-string or blank entry");
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    /** Surfacing-friendly wrapper for parse failures. */
    public static class RecipeParseException extends RuntimeException {
        public RecipeParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
