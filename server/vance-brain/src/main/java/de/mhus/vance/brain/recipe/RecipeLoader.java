package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.session.DisconnectPolicy;
import de.mhus.vance.api.session.IdlePolicy;
import de.mhus.vance.api.session.SessionLifecycleConfig;
import de.mhus.vance.api.session.SuspendPolicy;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
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
    public static final String RECIPE_PATH_PREFIX = "_vance/recipes/";

    /** File suffix kept on the document path; the recipe name itself does not carry it. */
    public static final String RECIPE_PATH_SUFFIX = ".yaml";

    private final DocumentService documentService;
    private final PromptTemplateRenderer templateRenderer;

    /**
     * Resolve {@code name} in the project/_vance/classpath cascade.
     * Returns empty if no tier carries the recipe.
     *
     * @throws RecipeParseException when the YAML at the matched layer
     *         is malformed, missing required fields, or carries a
     *         {@code promptPrefix} that fails Pebble compilation
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
            return Optional.of(parse(name.toLowerCase().trim(), result, templateRenderer));
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
        String project = effectiveProjectId(projectId);
        // 1. Top-level recipes: recipes/<name>.yaml (kit-installed,
        //    bundled, _tenant-overrides). DocumentService's
        //    matchesOneLevel filter keeps subdirectory contents out
        //    of this slice.
        Map<String, LookupResult> hits = new java.util.LinkedHashMap<>(
                documentService.listByPrefixCascade(
                        tenantId, project, RECIPE_PATH_PREFIX));
        // 2. User-namespace recipes: recipes/_user/<name>.yaml (Slart
        //    Phase-D persists named CREATEs here). Without this
        //    explicit pass, recipe_list and the unknown-recipe error
        //    message hide them — LLMs that just named a recipe via
        //    Slart wouldn't see it on the next turn.
        Map<String, LookupResult> userHits = documentService.listByPrefixCascade(
                tenantId, project, RECIPE_PATH_PREFIX + "_user/");
        for (Map.Entry<String, LookupResult> e : userHits.entrySet()) {
            hits.putIfAbsent(e.getKey(), e.getValue());
        }
        List<ResolvedRecipe> out = new ArrayList<>(hits.size());
        for (Map.Entry<String, LookupResult> e : hits.entrySet()) {
            String path = e.getKey();
            String name = nameFromPath(path);
            if (name == null) continue;
            try {
                out.add(parse(name, e.getValue(), templateRenderer));
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
                ? HomeBootstrapService.TENANT_PROJECT_NAME : projectId;
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
    private static ResolvedRecipe parse(
            String name, LookupResult hit, PromptTemplateRenderer renderer) {
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
        // Compile-validate the template now so a syntax error fails the
        // recipe load, not the first turn that picks the recipe.
        compileTemplate(renderer, promptPrefix, "promptPrefix");
        PromptMode promptMode = parsePromptMode(spec.get("promptMode"));
        String dataRelayCorrection = stringOrNull(spec.get("dataRelayCorrection"));
        List<String> add = stringList(spec.get("allowedToolsAdd"), "allowedToolsAdd");
        List<String> remove = stringList(spec.get("allowedToolsRemove"), "allowedToolsRemove");
        List<String> defer = stringList(spec.get("allowedToolsDefer"), "allowedToolsDefer");
        Map<String, RecipeModeBlock> baseModes = parseModes(spec.get("modes"), "modes");
        Map<String, ProfileBlock> profiles = parseProfiles(spec.get("profiles"), renderer);
        List<String> defaultActiveSkills = stringList(
                spec.get("defaultActiveSkills"), "defaultActiveSkills");
        List<String> allowedSkills = parseAllowedSkills(spec.get("allowedSkills"));
        validateDefaultsAreAllowed(name, defaultActiveSkills, allowedSkills);
        List<String> triggerKeywords = parseTriggerKeywords(spec.get("triggers"));
        boolean locked = spec.get("locked") instanceof Boolean b && b;
        boolean internal = spec.get("internal") instanceof Boolean ib && ib;
        boolean listed = spec.get("listed") instanceof Boolean lb && lb;
        String title = stringOrNull(spec.get("title"));
        List<String> tags = stringList(spec.get("tags"), "tags");
        PostCompletionHookConfig postCompletionHook =
                parsePostCompletionHook(spec.get("postCompletionHook"), renderer);

        return new ResolvedRecipe(
                name, description, engine, params,
                promptPrefix, promptMode,
                dataRelayCorrection,
                add, remove, defer, baseModes, profiles,
                defaultActiveSkills, allowedSkills,
                triggerKeywords,
                locked, internal, listed, title, tags,
                postCompletionHook,
                mapSource(hit.source()));
    }

    /**
     * Parses the optional {@code postCompletionHook} block. {@code null}
     * raw → returns {@code null} (no hook). Validates structural
     * correctness and compile-checks the {@code goalTemplate} so a
     * malformed Pebble template fails the recipe load, not the first
     * worker turn that would have spawned the hook.
     *
     * <p>Cross-recipe checks (hook recipe exists, hook recipe uses
     * Lunkwill engine, hook recipe has no transitive
     * {@code postCompletionHook}) are deferred to engine spawn-time
     * because they need cascade resolution against a concrete tenant —
     * and the loader operates per-recipe without that scope.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable PostCompletionHookConfig parsePostCompletionHook(
            @Nullable Object raw, PromptTemplateRenderer renderer) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("'postCompletionHook' must be a map");
        }
        Map<String, Object> m = (Map<String, Object>) rawMap;
        String hookRecipe = stringOrNull(m.get("recipe"));
        if (hookRecipe == null) {
            throw new IllegalStateException(
                    "'postCompletionHook.recipe' is required and must be non-blank");
        }
        PostCompletionHookTrigger trigger =
                parseHookTrigger(m.get("trigger"));
        int maxRounds = parseMaxRounds(m.get("maxRounds"));
        String goalTemplate = stringOrNull(m.get("goalTemplate"));
        compileTemplate(renderer, goalTemplate, "postCompletionHook.goalTemplate");
        return new PostCompletionHookConfig(
                hookRecipe, trigger, maxRounds, goalTemplate);
    }

    private static PostCompletionHookTrigger parseHookTrigger(@Nullable Object raw) {
        if (raw == null) return PostCompletionHookTrigger.NATURAL_STOP;
        if (!(raw instanceof String s)) {
            throw new IllegalStateException(
                    "'postCompletionHook.trigger' must be a string");
        }
        String norm = s.trim();
        // Accept the YAML-friendly camelCase aliases used in the design
        // doc ('naturalStop', 'terminate', 'both') in addition to the
        // canonical enum names. Keeps recipes readable without forcing
        // SHOUTING_SNAKE in the YAML.
        return switch (norm.toLowerCase()) {
            case "naturalstop", "natural_stop" -> PostCompletionHookTrigger.NATURAL_STOP;
            case "terminate" -> PostCompletionHookTrigger.TERMINATE;
            case "both" -> PostCompletionHookTrigger.BOTH;
            default -> throw new IllegalStateException(
                    "unknown postCompletionHook.trigger '" + s
                            + "' — expected naturalStop, terminate, or both");
        };
    }

    private static int parseMaxRounds(@Nullable Object raw) {
        if (raw == null) return 1;
        if (raw instanceof Number n) {
            int v = n.intValue();
            if (v < 0) {
                throw new IllegalStateException(
                        "'postCompletionHook.maxRounds' must be >= 0");
            }
            return v;
        }
        throw new IllegalStateException(
                "'postCompletionHook.maxRounds' must be a non-negative integer");
    }

    /**
     * Parses {@code triggers.keywords} (nested YAML). Normalises every
     * entry to lower-case and trims whitespace so the selector pre-check
     * can compare without per-call work. Blank entries are dropped,
     * duplicates de-duped while keeping first-occurrence order.
     *
     * <p>Returns an empty list when the {@code triggers} block is missing
     * or contains no {@code keywords} field. The block itself may carry
     * other (future) sub-fields without breaking this loader.
     */
    @SuppressWarnings("unchecked")
    private static List<String> parseTriggerKeywords(@Nullable Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("'triggers' must be a map");
        }
        Object keywordsRaw = ((Map<String, Object>) rawMap).get("keywords");
        if (keywordsRaw == null) return List.of();
        if (!(keywordsRaw instanceof List<?> list)) {
            throw new IllegalStateException("'triggers.keywords' must be a list");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s)) {
                throw new IllegalStateException(
                        "'triggers.keywords' contains a non-string entry");
            }
            String norm = s.trim().toLowerCase();
            if (norm.isEmpty()) continue;
            if (!out.contains(norm)) {
                out.add(norm);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Compile-validate a Pebble template and surface a clean recipe-load
     * error. {@code null} / blank templates are no-ops (they mean "no
     * override" — there's nothing to compile).
     */
    private static void compileTemplate(
            PromptTemplateRenderer renderer,
            @Nullable String template,
            String fieldName) {
        if (template == null || template.isBlank()) return;
        try {
            renderer.compile(template);
        } catch (PromptTemplateException e) {
            throw new IllegalStateException(
                    "'" + fieldName + "' is not a valid Pebble template: " + e.getMessage(), e);
        }
    }

    /**
     * Parses the optional {@code allowedSkills} field. Distinguishes
     * "missing key" (returns {@code null} → no restriction) from
     * "empty list" (returns empty → lockdown).
     */
    @SuppressWarnings("unchecked")
    private static @Nullable List<String> parseAllowedSkills(@Nullable Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'allowedSkills' must be a list");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalStateException(
                        "'allowedSkills' contains a non-string or blank entry");
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    private static void validateDefaultsAreAllowed(
            String recipeName,
            List<String> defaultActiveSkills,
            @Nullable List<String> allowedSkills) {
        if (allowedSkills == null) return;
        for (String name : defaultActiveSkills) {
            if (!allowedSkills.contains(name)) {
                throw new IllegalStateException(
                        "recipe '" + recipeName + "': defaultActiveSkill '"
                                + name + "' is not in allowedSkills");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ProfileBlock> parseProfiles(
            Object raw, PromptTemplateRenderer renderer) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("'profiles' must be a map");
        }
        Map<String, ProfileBlock> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.isBlank()) {
                throw new IllegalStateException("'profiles' contains a blank key");
            }
            Object blockRaw = entry.getValue();
            if (blockRaw == null) {
                out.put(key, ProfileBlock.EMPTY);
                continue;
            }
            if (!(blockRaw instanceof Map<?, ?>)) {
                throw new IllegalStateException(
                        "'profiles." + key + "' must be a map");
            }
            Map<String, Object> blockMap = (Map<String, Object>) blockRaw;
            List<String> blockAdd = stringList(
                    blockMap.get("allowedToolsAdd"),
                    "profiles." + key + ".allowedToolsAdd");
            List<String> blockRemove = stringList(
                    blockMap.get("allowedToolsRemove"),
                    "profiles." + key + ".allowedToolsRemove");
            List<String> blockDefer = stringList(
                    blockMap.get("allowedToolsDefer"),
                    "profiles." + key + ".allowedToolsDefer");
            Map<String, RecipeModeBlock> blockModes = parseModes(
                    blockMap.get("modes"), "profiles." + key + ".modes");
            String blockAppend = stringOrNull(blockMap.get("promptPrefixAppend"));
            compileTemplate(renderer, blockAppend, "profiles." + key + ".promptPrefixAppend");
            Map<String, Object> blockParams = new LinkedHashMap<>();
            Object rawBlockParams = blockMap.get("params");
            if (rawBlockParams != null) {
                if (!(rawBlockParams instanceof Map<?, ?> bp)) {
                    throw new IllegalStateException(
                            "'profiles." + key + ".params' must be a map");
                }
                for (Map.Entry<?, ?> p : bp.entrySet()) {
                    blockParams.put(String.valueOf(p.getKey()), p.getValue());
                }
            }
            SessionLifecycleConfig sessionCfg = parseSessionBlock(
                    blockMap.get("session"), "profiles." + key + ".session");
            out.put(key, new ProfileBlock(
                    blockAdd, blockRemove, blockDefer, blockModes,
                    blockAppend, Map.copyOf(blockParams), sessionCfg));
        }
        return Map.copyOf(out);
    }

    /**
     * Parses an optional {@code modes:} sub-map. Each mode key holds a
     * {@link RecipeModeBlock} with optional {@code allowedToolsAdd /
     * Remove / Defer} lists. The literal mode key {@code default}
     * is the catch-all for modes the recipe didn't list explicitly.
     * An empty block ({@code modes.<X>: {}}) is preserved as
     * {@link RecipeModeBlock#EMPTY} — it acts as an explicit "leave
     * the profile-base in place for this mode" marker (see §14.4).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, RecipeModeBlock> parseModes(@Nullable Object raw, String fieldName) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("'" + fieldName + "' must be a map");
        }
        Map<String, RecipeModeBlock> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.isBlank()) {
                throw new IllegalStateException("'" + fieldName + "' contains a blank key");
            }
            Object blockRaw = entry.getValue();
            if (blockRaw == null) {
                out.put(key, RecipeModeBlock.EMPTY);
                continue;
            }
            if (!(blockRaw instanceof Map<?, ?>)) {
                throw new IllegalStateException(
                        "'" + fieldName + "." + key + "' must be a map");
            }
            Map<String, Object> blockMap = (Map<String, Object>) blockRaw;
            List<String> add = stringList(
                    blockMap.get("allowedToolsAdd"),
                    fieldName + "." + key + ".allowedToolsAdd");
            List<String> remove = stringList(
                    blockMap.get("allowedToolsRemove"),
                    fieldName + "." + key + ".allowedToolsRemove");
            List<String> defer = stringList(
                    blockMap.get("allowedToolsDefer"),
                    fieldName + "." + key + ".allowedToolsDefer");
            out.put(key, new RecipeModeBlock(add, remove, defer));
        }
        return Map.copyOf(out);
    }

    /**
     * Parses the optional {@code session} sub-block inside a profile.
     * Each missing field falls through to the safeDefault values; an
     * entirely missing block returns {@code null}.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable SessionLifecycleConfig parseSessionBlock(
            @Nullable Object raw, String fieldName) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("'" + fieldName + "' must be a map");
        }
        Map<String, Object> sm = (Map<String, Object>) rawMap;
        SessionLifecycleConfig.SessionLifecycleConfigBuilder b =
                SessionLifecycleConfig.builder();
        Object onDisconnect = sm.get("onDisconnect");
        if (onDisconnect != null) {
            b.onDisconnect(parseEnum(DisconnectPolicy.class, onDisconnect, fieldName + ".onDisconnect"));
        }
        Object onIdle = sm.get("onIdle");
        if (onIdle != null) {
            b.onIdle(parseEnum(IdlePolicy.class, onIdle, fieldName + ".onIdle"));
        }
        Object onSuspend = sm.get("onSuspend");
        if (onSuspend != null) {
            b.onSuspend(parseEnum(SuspendPolicy.class, onSuspend, fieldName + ".onSuspend"));
        }
        Object idleTimeoutMs = sm.get("idleTimeoutMs");
        if (idleTimeoutMs != null) {
            b.idleTimeoutMs(parseLong(idleTimeoutMs, fieldName + ".idleTimeoutMs"));
        }
        Object suspendKeepDurationMs = sm.get("suspendKeepDurationMs");
        if (suspendKeepDurationMs != null) {
            b.suspendKeepDurationMs(parseLong(suspendKeepDurationMs, fieldName + ".suspendKeepDurationMs"));
        }
        return b.build();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, Object raw, String fieldName) {
        if (!(raw instanceof String s)) {
            throw new IllegalStateException("'" + fieldName + "' must be a string");
        }
        try {
            return Enum.valueOf(type, s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "unknown " + type.getSimpleName() + " value '" + s + "' for '" + fieldName + "'");
        }
    }

    private static long parseLong(Object raw, String fieldName) {
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("'" + fieldName + "' is not a number: " + s);
            }
        }
        throw new IllegalStateException("'" + fieldName + "' must be a number");
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
