package de.mhus.vance.brain.recipe;

import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Cascade resolver for recipe names — Project → {@code _vance} →
 * classpath, all reached through {@link RecipeLoader} which in turn
 * delegates to {@code DocumentService.lookupCascade}. Returns
 * {@link AppliedRecipe} with caller-supplied param overrides already
 * folded in (unless the recipe is locked).
 *
 * <p>Lazy {@link ObjectProvider} for {@link ThinkEngineService}
 * because the bean graph cycles otherwise:
 * {@code ThinkEngineService → ToolDispatcher → ProcessCreateTool →
 * RecipeResolver → ThinkEngineService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeResolver {

    private final RecipeLoader loader;
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final ServerToolService serverToolService;

    /** Prefix that marks an entry as a label selector (vs. a literal tool name). */
    public static final String LABEL_PREFIX = "@";

    /**
     * Resolves {@code name} in the project/_vance/classpath cascade.
     * Returns empty if no tier carries the name — the caller will
     * normally turn this into a 4xx-style error for the LLM/REST API.
     */
    public Optional<ResolvedRecipe> resolve(
            String tenantId, @Nullable String projectId, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return loader.load(tenantId, effectiveProjectId(projectId), name);
    }

    /**
     * Full pipeline: resolve the recipe, merge caller params, compute
     * the effective allowed-tools set against the resolved engine.
     * The returned {@link AppliedRecipe} carries everything the
     * spawner needs to write onto the fresh {@code ThinkProcessDocument}.
     *
     * @throws UnknownRecipeException when the cascade has no match
     * @throws UnknownEngineException when the recipe references an
     *         engine the registry doesn't know about
     */
    public AppliedRecipe apply(
            String tenantId,
            @Nullable String projectId,
            String name,
            @Nullable Map<String, Object> callerParams) {
        ResolvedRecipe r = resolve(tenantId, projectId, name)
                .orElseThrow(() -> new UnknownRecipeException(name));

        ThinkEngine engine = thinkEngineServiceProvider.getObject().resolve(r.engine())
                .orElseThrow(() -> new UnknownEngineException(
                        "Recipe '" + r.name() + "' references unknown engine '"
                                + r.engine() + "'"));

        Map<String, Object> mergedParams = new LinkedHashMap<>(r.params());
        List<String> overriddenKeys = new ArrayList<>();
        if (callerParams != null && !callerParams.isEmpty()) {
            if (r.locked()) {
                log.warn("RecipeResolver: recipe='{}' is locked — ignoring caller params {}",
                        r.name(), callerParams.keySet());
            } else {
                for (Map.Entry<String, Object> e : callerParams.entrySet()) {
                    if (mergedParams.containsKey(e.getKey())) {
                        overriddenKeys.add(e.getKey());
                    }
                    mergedParams.put(e.getKey(), e.getValue());
                }
                if (!overriddenKeys.isEmpty()) {
                    log.info("RecipeResolver: recipe='{}' override applied — overridden_keys={} source={}",
                            r.name(), overriddenKeys, r.source());
                }
            }
        }

        // Default the user-progress verbosity if the recipe didn't pin
        // it (and the caller didn't override it). NORMAL = metrics +
        // tool-boundary status, no engine-INFO chatter. Engines never
        // hard-code the key, so it has to surface here.
        mergedParams.putIfAbsent(
                de.mhus.vance.brain.progress.ProgressLevel.PARAM_KEY,
                de.mhus.vance.brain.progress.ProgressLevel.NORMAL.name().toLowerCase());

        List<String> expandedAdd = expandLabelSelectors(
                tenantId, projectId, r.allowedToolsAdd());
        List<String> expandedRemove = expandLabelSelectors(
                tenantId, projectId, r.allowedToolsRemove());

        Set<String> effectiveAllowed = computeAllowed(
                engine.allowedTools(), expandedAdd, expandedRemove);

        return new AppliedRecipe(
                r.name(),
                r.engine(),
                mergedParams,
                r.promptPrefix(),
                r.promptPrefixSmall(),
                r.promptMode(),
                r.intentCorrection(),
                r.dataRelayCorrection(),
                effectiveAllowed,
                r.source(),
                List.copyOf(overriddenKeys));
    }

    /**
     * Expands any {@code @<label>} selector in {@code entries} to the
     * concrete tool names carrying that label, looked up through
     * {@link ServerToolService#findByLabel}. Literal entries (without
     * the {@code @} prefix) pass through unchanged. Result is
     * deduplicated while preserving first-seen order — the snapshot
     * gets persisted on the spawned process and must be deterministic.
     *
     * <p>An unresolved label (no tools carry it) silently expands to
     * the empty list. The recipe author can use that as a feature
     * (label is "optional") — and it avoids spawn failures when a
     * label-bearing tool is removed at runtime.
     */
    private List<String> expandLabelSelectors(
            String tenantId, @Nullable String projectId, List<String> entries) {
        if (entries == null || entries.isEmpty()) return List.of();
        boolean hasSelector = false;
        for (String e : entries) {
            if (e != null && e.startsWith(LABEL_PREFIX) && e.length() > 1) {
                hasSelector = true;
                break;
            }
        }
        if (!hasSelector) return entries;
        String effectiveProjectId = effectiveProjectId(projectId);
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            if (entry.startsWith(LABEL_PREFIX) && entry.length() > 1) {
                String label = entry.substring(LABEL_PREFIX.length());
                for (Tool t : serverToolService.findByLabel(
                        tenantId, effectiveProjectId, label)) {
                    if (seen.add(t.name())) out.add(t.name());
                }
            } else if (seen.add(entry)) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Computes the effective allowed-tool set:
     * {@code (engineDefault ∪ add) ∖ remove}. Returns {@code null} if
     * the result equals the engine default — the spawner then leaves
     * {@code allowedToolsOverride} empty on the process.
     */
    private static @Nullable Set<String> computeAllowed(
            Set<String> engineDefault,
            List<String> add,
            List<String> remove) {
        boolean addPresent = add != null && !add.isEmpty();
        boolean removePresent = remove != null && !remove.isEmpty();
        if (!addPresent && !removePresent) {
            return null; // no adjustment
        }
        Set<String> effective = new LinkedHashSet<>(engineDefault);
        if (addPresent) effective.addAll(add);
        if (removePresent) remove.forEach(effective::remove);
        // Compare to engine default for the same content.
        if (effective.equals(engineDefault)) {
            return null;
        }
        return Set.copyOf(effective);
    }

    /**
     * Applies the spawn-cascade for a {@code process_create}-style
     * call where the caller may have given a recipe, an engine, or
     * neither:
     *
     * <ol>
     *   <li>If {@code recipeName} is set → resolve it (error if
     *       missing).</li>
     *   <li>Else if {@code engineName} is set → try to resolve a
     *       recipe with that name. If found, use it. If not,
     *       returns empty so the caller can take the engine-direct
     *       fallback (no recipe overrides applied).</li>
     *   <li>Else → resolve recipe {@code "default"} (error if
     *       missing — the bundled YAML must contain it).</li>
     * </ol>
     *
     * <p>This puts the entire defaulting policy in one place; the
     * three create-paths (tool, handler, bootstrap, plus the
     * session-chat bootstrapper) all call it.
     *
     * @return the applied recipe, or {@link Optional#empty()} only
     *         when {@code engineName} is set and no matching recipe
     *         exists. Other "missing recipe" cases throw.
     */
    public Optional<AppliedRecipe> applyDefaulting(
            String tenantId,
            @Nullable String projectId,
            @Nullable String recipeName,
            @Nullable String engineName,
            @Nullable Map<String, Object> callerParams) {
        if (recipeName != null && !recipeName.isBlank()) {
            return Optional.of(apply(tenantId, projectId, recipeName, callerParams));
        }
        if (engineName != null && !engineName.isBlank()) {
            // Engine-direct path with auto-recipe-by-engine-name.
            if (resolve(tenantId, projectId, engineName).isPresent()) {
                return Optional.of(apply(tenantId, projectId, engineName, callerParams));
            }
            return Optional.empty(); // caller falls back to engine-direct
        }
        return Optional.of(apply(tenantId, projectId, "default", callerParams));
    }

    private static String effectiveProjectId(@Nullable String projectId) {
        return (projectId == null || projectId.isBlank())
                ? HomeBootstrapService.VANCE_PROJECT_NAME : projectId;
    }

    public static class UnknownRecipeException extends RuntimeException {
        public UnknownRecipeException(String name) {
            super("Unknown recipe '" + name + "'");
        }
    }

    public static class UnknownEngineException extends RuntimeException {
        public UnknownEngineException(String message) {
            super(message);
        }
    }
}
