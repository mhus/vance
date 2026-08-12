package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
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
    /**
     * Client-registered tools ({@code ~/.vancetope/foot-tools/*.json}
     * packs plus the foot's hand-coded beans) participate in label
     * selectors too — see {@link #expandLabelSelectors}. Lazy provider
     * so the recipe layer stays usable in contexts without the WS
     * stack (anus, tests).
     */
    private final ObjectProvider<ClientToolRegistry> clientToolRegistryProvider;

    /** Prefix that marks an entry as a label selector (vs. a literal tool name). */
    public static final String LABEL_PREFIX = "@";

    /**
     * Reserved mode-block key used as the catch-all for engine modes
     * the recipe doesn't list explicitly. Mirrors the {@code default}
     * key in the profile cascade. See {@code planning/tool-schema-deferral.md} §14.4.
     */
    public static final String MODE_DEFAULT_KEY = "default";

    /**
     * Per-turn tool-filter resolved against the recipe's mode/profile
     * cascade. Each list contains literal tool names — label selectors
     * (`@<label>`) have been expanded by the resolver. Override
     * semantics: this triple comes from a single cascade hit; outer
     * layers are not merged in. See
     * {@code planning/tool-schema-deferral.md} §14.5.
     */
    public record ToolFilter(
            List<String> remove,
            List<String> add,
            List<String> defer,
            List<String> keep,
            List<String> dropFirst) {

        /** Empty filter — no overlays applied. */
        public static final ToolFilter EMPTY =
                new ToolFilter(List.of(), List.of(), List.of(), List.of(), List.of());

        /**
         * Visibility-only filter without budget-priority hints. Keeps the
         * three-list call sites that predate the tool-surface budget.
         */
        public ToolFilter(List<String> remove, List<String> add, List<String> defer) {
            this(remove, add, defer, List.of(), List.of());
        }

        /**
         * {@code true} when no overlay applies. {@code keep}/{@code dropFirst}
         * count: they carry no visibility effect, but a recipe that only
         * ranks tools still has something to say to the budget stage.
         */
        public boolean isEmpty() {
            return remove.isEmpty() && add.isEmpty() && defer.isEmpty()
                    && keep.isEmpty() && dropFirst.isEmpty();
        }
    }

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
        String effectiveProject = effectiveProjectId(projectId);

        // 1. Exact match in the cascade (project → _tenant → bundled).
        Optional<ResolvedRecipe> exact = loader.load(tenantId, effectiveProject, name);
        if (exact.isPresent()) return exact;

        // 2. User-namespace fallback. Slartibartfast persists named
        //    recipes under `_user/<name>` (Phase-D convention), but
        //    chat users refer to them by short name ("rat-der-macher",
        //    not "_user/rat-der-macher"). When the caller passes a
        //    bare name without a slash, retry once in the user
        //    namespace before declaring the recipe unknown. Skipped
        //    when the caller already specified a path (contains '/'),
        //    so explicit `_slart/<runId>/<name>` lookups don't loop.
        if (!name.contains("/")) {
            Optional<ResolvedRecipe> userScoped =
                    loader.load(tenantId, effectiveProject, "_user/" + name);
            if (userScoped.isPresent()) {
                log.info("RecipeResolver: short name '{}' resolved via "
                                + "_user/-namespace fallback → '{}'",
                        name, userScoped.get().name());
                return userScoped;
            }
        }

        return Optional.empty();
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
            @Nullable String connectionProfile,
            @Nullable Map<String, Object> callerParams) {
        ResolvedRecipe r = resolve(tenantId, projectId, name)
                .orElseThrow(() -> new UnknownRecipeException(name));

        ThinkEngine engine = thinkEngineServiceProvider.getObject().resolve(r.engine())
                .orElseThrow(() -> new UnknownEngineException(
                        "Recipe '" + r.name() + "' references unknown engine '"
                                + r.engine() + "'"));

        // Profile-block cascade: exact match → "default" key → empty.
        // Open-string semantics — unknown profiles silently fall through.
        ProfileBlock profileBlock = resolveProfileBlock(r, connectionProfile);

        // Param merge order: recipe.params → profile.params → callerParams.
        // Profile-params apply even when recipe is locked (recipe-author
        // intent, not caller override). Caller-params skipped on locked.
        Map<String, Object> mergedParams = new LinkedHashMap<>(r.params());
        mergedParams.putAll(profileBlock.params());
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

        // Spawn-time dispatcher pool: recipe + profile-base lists fold in
        // here. Defer-listed tools count as "add to dispatch" — they need
        // to be invokable so the LLM can activate them via tool_description;
        // the per-turn ToolFilter handles the primary-vs-deferred split.
        // Per-mode mode-block lists are NOT folded here; the per-turn
        // toolFilterFor() applies them live.
        List<String> combinedAdd = concat(
                r.allowedToolsAdd(), profileBlock.allowedToolsAdd(),
                r.allowedToolsDefer(), profileBlock.allowedToolsDefer());
        List<String> combinedRemove = concat(r.allowedToolsRemove(), profileBlock.allowedToolsRemove());
        List<String> expandedAdd = expandLabelSelectors(tenantId, projectId, combinedAdd);
        List<String> expandedRemove = expandLabelSelectors(tenantId, projectId, combinedRemove);

        Set<String> effectiveAllowed = computeAllowed(
                engine.allowedTools(), expandedAdd, expandedRemove);

        // Recipe override and profile-append are kept separate so the
        // recipe template can place {{ profileAppend }} at any position
        // in its body. SystemPrompts.compose renders both; if the
        // template doesn't reference the variable AND profile-append is
        // non-blank, the renderer falls back to legacy auto-append at
        // the end. See planning/prompt-inlining.md §3.
        String effectivePromptOverride = r.promptPrefix();
        String effectivePromptOverrideAppend = profileBlock.promptPrefixAppend();

        return new AppliedRecipe(
                r.name(),
                r.engine(),
                mergedParams,
                effectivePromptOverride,
                effectivePromptOverrideAppend,
                r.promptMode(),
                r.dataRelayCorrection(),
                effectiveAllowed,
                connectionProfile,
                r.defaultActiveSkills(),
                r.allowedSkills(),
                r.source(),
                List.copyOf(overriddenKeys),
                profileBlock.sessionLifecycleConfig());
    }

    /**
     * Resolves the profile-block for {@code connectionProfile} via the
     * cascade {@code exact-match → "default" → empty}. {@code null}/blank
     * profile name skips the exact-match step and goes straight to
     * "default" (then empty) — same semantics as an unknown profile.
     */
    private static ProfileBlock resolveProfileBlock(
            ResolvedRecipe r, @Nullable String connectionProfile) {
        Map<String, ProfileBlock> profiles = r.profiles();
        if (profiles == null || profiles.isEmpty()) {
            return ProfileBlock.EMPTY;
        }
        if (connectionProfile != null && !connectionProfile.isBlank()) {
            ProfileBlock exact = profiles.get(connectionProfile);
            if (exact != null) return exact;
        }
        ProfileBlock fallback = profiles.get(Profiles.DEFAULT);
        return fallback == null ? ProfileBlock.EMPTY : fallback;
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> out = new ArrayList<>();
        for (List<String> l : lists) {
            if (l != null && !l.isEmpty()) out.addAll(l);
        }
        return out;
    }

    /**
     * Expands any {@code @<label>} selector in {@code entries} to the
     * concrete tool names carrying that label. Two lookup sources:
     * {@link ServerToolService#findByLabel} for server-managed tools,
     * and — when {@code ctx} carries a {@code sessionId} — the session's
     * {@link ClientToolRegistry} registration for client-side tools
     * (foot MCP/REST packs from {@code ~/.vancetope/foot-tools/*.json},
     * whose labels reach us on {@code ToolSpec.labels}). Literal entries
     * (without the {@code @} prefix) pass through unchanged. Result is
     * deduplicated while preserving first-seen order — the snapshot
     * gets persisted on the spawned process and must be deterministic.
     *
     * <p>Client tools are therefore only reachable through the per-turn
     * path ({@link #toolFilterFor}), never through the spawn path, which
     * has no session scope. That is deliberate: a foot may re-register a
     * different pack set at any time ({@code /tools reload}), so a
     * name list frozen into {@code allowedToolsOverride} would go stale.
     * {@code ContextToolsApi.classify} closes the loop by admitting
     * per-turn {@code add} entries into the dispatch pool.
     *
     * <p>An unresolved label (no tools carry it) silently expands to
     * the empty list. The recipe author can use that as a feature
     * (label is "optional") — and it avoids spawn failures when a
     * label-bearing tool is removed at runtime.
     */
    private List<String> expandLabelSelectors(
            String tenantId, @Nullable String projectId, List<String> entries) {
        return expandLabelSelectors(tenantId, projectId, entries, /*ctx*/ null);
    }

    private List<String> expandLabelSelectors(
            String tenantId, @Nullable String projectId, List<String> entries,
            @Nullable ToolInvocationContext ctx) {
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
                        tenantId, effectiveProjectId, label, ctx)) {
                    if (seen.add(t.name())) out.add(t.name());
                }
                for (String name : clientToolNamesByLabel(label, ctx)) {
                    if (seen.add(name)) out.add(name);
                }
            } else if (seen.add(entry)) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Names of the session's client-registered tools carrying
     * {@code label}. Empty when there is no session scope (spawn path),
     * no registration for it (client disconnected), or no WS stack at
     * all — the label then behaves like any other unresolved selector.
     */
    private List<String> clientToolNamesByLabel(
            String label, @Nullable ToolInvocationContext ctx) {
        if (ctx == null || ctx.sessionId() == null || ctx.sessionId().isBlank()) {
            return List.of();
        }
        ClientToolRegistry registry = clientToolRegistryProvider.getIfAvailable();
        if (registry == null) return List.of();
        List<String> out = new ArrayList<>();
        for (ToolSpec spec : registry.toolsFor(ctx.sessionId())) {
            Set<String> labels = spec.getLabels();
            if (labels != null && labels.contains(label)) out.add(spec.getName());
        }
        return out;
    }

    /**
     * Computes the effective allowed-tool set:
     * {@code (engineDefault ∪ add) ∖ remove}. Returns {@code null} if
     * the result equals the engine default — the spawner then leaves
     * {@code allowedToolsOverride} empty on the process.
     *
     * <p>Special case: when {@code engineDefault} is empty
     * ({@link ThinkEngine#allowedTools()} default, the "no engine-level
     * restriction" signal — Ford and friends), an {@code add} list at
     * the recipe layer must NOT collapse the allow-set to just the
     * added entries. That would hide the rest of the catalog
     * ({@code workspace_*}, {@code tool_list}, {@code tool_description},
     * …) and brick the worker. The per-turn {@code ToolFilter} still
     * carries the add list and uses it for primary-vs-deferred
     * classification, which is the actual intent of
     * {@code allowedToolsAdd} in this case. {@code remove}-only at the
     * recipe layer is similarly handled by the per-turn filter, so
     * empty engineDefault + remove also stays null at spawn.
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
        if (engineDefault.isEmpty()) {
            // No engine-level restriction → keep "no restriction" at spawn.
            // The per-turn ToolFilter handles add/remove/defer instead.
            return null;
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
     * Per-turn tool-filter resolution. The cascade in
     * {@code planning/tool-schema-deferral.md} §14.4 applied
     * first-hit-wins (override, not accumulation):
     *
     * <ol>
     *   <li>{@code recipe.profiles[profile].modes[mode]}</li>
     *   <li>{@code recipe.profiles[profile].modes["default"]}</li>
     *   <li>{@code recipe.profiles[profile]} (profile-base, no mode overlay)</li>
     *   <li>{@code recipe.profiles["default"]}</li>
     *   <li>{@code recipe.profiles["default"].modes[mode]} → recipe-base
     *       mode entry — used when there's no profile match at all.</li>
     *   <li>{@code recipe.profiles["default"].modes["default"]}</li>
     *   <li>recipe-base ({@code allowedToolsAdd / Remove / Defer}) and its
     *       {@code modes[mode]}, {@code modes["default"]}.</li>
     * </ol>
     *
     * <p>Returns {@link ToolFilter#EMPTY} when neither the recipe nor
     * any matching layer carries an overlay — callers then apply only
     * the engine default + {@code Tool.deferred()}-classification.
     *
     * <p>Engines with no mode (Ford, Marvin, Eddie) pass {@code mode = NORMAL}
     * (or {@code null}); those land on the profile-base layer of the cascade.
     *
     * @param recipeName       resolved process recipe name; null/blank → resolve "default"
     * @param connectionProfile profile name (foot/web/...) — matches the
     *                          {@code profiles[profile]} key
     * @param mode             current process mode, NORMAL when not Plan-Mode
     */
    public ToolFilter toolFilterFor(
            String tenantId,
            @Nullable String projectId,
            @Nullable String recipeName,
            @Nullable String connectionProfile,
            @Nullable ProcessMode mode) {
        return toolFilterFor(tenantId, projectId, recipeName, connectionProfile, mode, /*ctx*/ null);
    }

    /**
     * Context-aware overload. {@code ctx} flows through into the label-
     * selector expansion so that user-scoped tool factories (MCP-server
     * with OAuth, REST-API with per-user tokens) materialise with the
     * caller's user identity. Without this, a recipe that pulls in a
     * label like {@code @jira} during turn setup would warm up the MCP
     * connection with {@code userId=null} and 401 against the remote
     * server — wasted RTT plus a cached failure that lingers until
     * next refresh.
     */
    public ToolFilter toolFilterFor(
            String tenantId,
            @Nullable String projectId,
            @Nullable String recipeName,
            @Nullable String connectionProfile,
            @Nullable ProcessMode mode,
            @Nullable ToolInvocationContext ctx) {
        String name = (recipeName != null && !recipeName.isBlank())
                ? recipeName : "default";
        Optional<ResolvedRecipe> resolved = resolve(tenantId, projectId, name);
        if (resolved.isEmpty()) {
            return ToolFilter.EMPTY;
        }
        ResolvedRecipe r = resolved.get();
        String modeKey = (mode == null) ? null : mode.name();
        ProfileBlock profileBlock = resolveProfileBlock(r, connectionProfile);
        RecipeModeBlock hit = lookupModeBlock(r, connectionProfile, modeKey);

        // Budget-priority hints are unioned along the cascade instead of
        // resolved first-hit-wins. They carry no visibility effect, so two
        // layers ranking different tools cannot contradict each other —
        // and a priority-only block must not shadow an outer add/defer.
        List<String> keep = new ArrayList<>();
        List<String> dropFirst = new ArrayList<>();
        collectPriority(tenantId, projectId, ctx,
                r.allowedToolsKeep(), r.allowedToolsDropFirst(), keep, dropFirst);
        collectPriority(tenantId, projectId, ctx,
                profileBlock.allowedToolsKeep(), profileBlock.allowedToolsDropFirst(),
                keep, dropFirst);
        if (hit != null) {
            collectPriority(tenantId, projectId, ctx,
                    hit.allowedToolsKeep(), hit.allowedToolsDropFirst(), keep, dropFirst);
        }

        // Visibility cascade: first matching block wins.
        if (hit != null && !hit.isEmpty()) {
            return expandFilter(tenantId, projectId, hit, ctx, keep, dropFirst);
        }

        // No mode-block hit. Fall through to profile-base / recipe-base.
        if (profileBlock.hasToolFilter()) {
            return new ToolFilter(
                    expandLabelSelectors(tenantId, projectId, profileBlock.allowedToolsRemove(), ctx),
                    expandLabelSelectors(tenantId, projectId, profileBlock.allowedToolsAdd(), ctx),
                    expandLabelSelectors(tenantId, projectId, profileBlock.allowedToolsDefer(), ctx),
                    List.copyOf(keep),
                    List.copyOf(dropFirst));
        }
        if (!r.allowedToolsAdd().isEmpty()
                || !r.allowedToolsRemove().isEmpty()
                || !r.allowedToolsDefer().isEmpty()) {
            return new ToolFilter(
                    expandLabelSelectors(tenantId, projectId, r.allowedToolsRemove(), ctx),
                    expandLabelSelectors(tenantId, projectId, r.allowedToolsAdd(), ctx),
                    expandLabelSelectors(tenantId, projectId, r.allowedToolsDefer(), ctx),
                    List.copyOf(keep),
                    List.copyOf(dropFirst));
        }
        if (!keep.isEmpty() || !dropFirst.isEmpty()) {
            // Ranking-only recipe: no visibility overlay, but the budget
            // stage still has something to go by.
            return new ToolFilter(List.of(), List.of(), List.of(),
                    List.copyOf(keep), List.copyOf(dropFirst));
        }
        return ToolFilter.EMPTY;
    }

    /**
     * Expands one layer's priority lists (label selectors included) into
     * the accumulating keep / drop-first sets. Duplicates are dropped so
     * repeated entries across layers stay harmless.
     */
    private void collectPriority(
            String tenantId,
            @Nullable String projectId,
            @Nullable ToolInvocationContext ctx,
            @Nullable List<String> keepIn,
            @Nullable List<String> dropFirstIn,
            List<String> keepOut,
            List<String> dropFirstOut) {
        for (String name : expandLabelSelectors(tenantId, projectId, keepIn, ctx)) {
            if (!keepOut.contains(name)) keepOut.add(name);
        }
        for (String name : expandLabelSelectors(tenantId, projectId, dropFirstIn, ctx)) {
            if (!dropFirstOut.contains(name)) dropFirstOut.add(name);
        }
    }

    /**
     * Walks the mode-block cascade. Returns the first non-empty
     * {@link RecipeModeBlock} encountered, or {@code null} when none
     * applies (caller falls through to profile-base / recipe-base).
     *
     * <p>Order: {@code profiles[profile].modes[mode] →
     * profiles[profile].modes["default"] → profiles["default"].modes[mode] →
     * profiles["default"].modes["default"] → recipe.modes[mode] →
     * recipe.modes["default"]}.
     */
    private static @Nullable RecipeModeBlock lookupModeBlock(
            ResolvedRecipe r, @Nullable String connectionProfile, @Nullable String modeKey) {
        if (modeKey == null) modeKey = MODE_DEFAULT_KEY;
        Map<String, ProfileBlock> profiles = r.profiles();
        // 1+2: exact profile, then default profile
        ProfileBlock[] profileChain = {
                profiles == null ? null
                        : (connectionProfile == null ? null : profiles.get(connectionProfile)),
                profiles == null ? null : profiles.get(Profiles.DEFAULT)
        };
        for (ProfileBlock pb : profileChain) {
            if (pb == null) continue;
            Map<String, RecipeModeBlock> modes = pb.modes();
            if (modes == null || modes.isEmpty()) continue;
            RecipeModeBlock exact = modes.get(modeKey);
            if (exact != null) return exact;
            RecipeModeBlock catchAll = modes.get(MODE_DEFAULT_KEY);
            if (catchAll != null) return catchAll;
        }
        // 3: recipe-base modes
        Map<String, RecipeModeBlock> baseModes = r.modes();
        if (baseModes == null || baseModes.isEmpty()) return null;
        RecipeModeBlock baseExact = baseModes.get(modeKey);
        if (baseExact != null) return baseExact;
        return baseModes.get(MODE_DEFAULT_KEY);
    }

    private ToolFilter expandFilter(
            String tenantId, @Nullable String projectId, RecipeModeBlock block) {
        return expandFilter(tenantId, projectId, block, /*ctx*/ null);
    }

    private ToolFilter expandFilter(
            String tenantId, @Nullable String projectId, RecipeModeBlock block,
            @Nullable ToolInvocationContext ctx) {
        return expandFilter(tenantId, projectId, block, ctx, List.of(), List.of());
    }

    /**
     * Variant that carries pre-resolved priority hints — those are unioned
     * across the cascade by {@link #toolFilterFor} rather than taken from
     * the winning block alone.
     */
    private ToolFilter expandFilter(
            String tenantId, @Nullable String projectId, RecipeModeBlock block,
            @Nullable ToolInvocationContext ctx,
            List<String> keep, List<String> dropFirst) {
        return new ToolFilter(
                expandLabelSelectors(tenantId, projectId, block.allowedToolsRemove(), ctx),
                expandLabelSelectors(tenantId, projectId, block.allowedToolsAdd(), ctx),
                expandLabelSelectors(tenantId, projectId, block.allowedToolsDefer(), ctx),
                List.copyOf(keep),
                List.copyOf(dropFirst));
    }

    /**
     * Applies the spawn-cascade for a {@code process_create}-style
     * call:
     *
     * <ol>
     *   <li>If {@code recipeName} is set → resolve it (error if
     *       missing).</li>
     *   <li>Else → resolve recipe {@code "default"} (error if
     *       missing — the bundled YAML must contain it).</li>
     * </ol>
     *
     * <p>This puts the entire defaulting policy in one place; the
     * four create-paths (tool, handler, bootstrap, plus the
     * session-chat bootstrapper) all call it. The engine-direct
     * spawn-path no longer exists — engine is always derived from
     * the recipe.
     */
    public AppliedRecipe applyDefaulting(
            String tenantId,
            @Nullable String projectId,
            @Nullable String recipeName,
            @Nullable String connectionProfile,
            @Nullable Map<String, Object> callerParams) {
        String effectiveRecipe = (recipeName != null && !recipeName.isBlank())
                ? recipeName : "default";
        return apply(tenantId, projectId, effectiveRecipe,
                connectionProfile, callerParams);
    }

    private static String effectiveProjectId(@Nullable String projectId) {
        return (projectId == null || projectId.isBlank())
                ? HomeBootstrapService.TENANT_PROJECT_NAME : projectId;
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
