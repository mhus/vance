package de.mhus.vance.brain.tools.process;

import de.mhus.vance.brain.delegate.RecipeSelectorService;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spawns a new think-process inside the current session. Single
 * entry-point with three routing modes — caller picks via the
 * {@code recipe} / {@code engine} params and the system fills in the
 * rest:
 *
 * <ul>
 *   <li><b>Explicit recipe.</b> {@code recipe="analyze"} →
 *       {@link RecipeResolver} cascades project → tenant → bundled,
 *       returns engine + default params + prompt-prefix + tool
 *       adjustments. Caller {@code params} merge on top unless the
 *       recipe is locked. Unknown recipe names are rejected with a
 *       {@link ToolException} that includes a close-match suggestion
 *       list — the LLM corrects the name on the next turn.</li>
 *   <li><b>Direct-engine.</b> {@code recipe} omitted (or "auto"),
 *       {@code engine="ford"} → no recipe lookup, just caller-supplied
 *       {@code params} on the named engine.</li>
 *   <li><b>Selector-routed.</b> Both {@code recipe} and {@code engine}
 *       omitted → the {@link RecipeSelectorService} runs its trigger
 *       pre-check on the supplied {@code goal} (recipe-name word
 *       boundary, trigger-keyword substring). A deterministic match
 *       spawns the matched recipe; a multi-candidate match calls the
 *       LLM for disambiguation; no trigger at all returns NONE.
 *       <br>
 *       <b>On NONE</b> the tool reads the setting
 *       {@code routing.fallback.recipe} (default {@code hactar}) and
 *       spawns that recipe so the user's goal still gets handled.
 *       Empty-string setting disables the fallback — the tool then
 *       returns NONE to the caller, which decides whether to ask the
 *       user or pick a recipe explicitly. The Slart-generation
 *       fallback that used to live here is gone — see
 *       {@code specification/recipe-routing.md} §6.</li>
 * </ul>
 *
 * <p>If both {@code recipe} and {@code engine} are set, {@code recipe}
 * wins and {@code engine} is logged-and-ignored — easier to reason
 * about than failing.
 *
 * <p>The new process is started immediately ({@code engine.start})
 * and arrives in
 * {@link de.mhus.vance.api.thinkprocess.ThinkProcessStatus#READY}.
 * Drive it via {@code process_steer}; stop it via {@code process_stop}.
 */
@Component
@Slf4j
@de.mhus.vance.toolpack.SpawnTool
public class ProcessCreateTool implements Tool {

    /**
     * Value treated as "no explicit recipe" — same outcome as omitting
     * the param entirely. Lets callers pass a literal sentinel when
     * their template needs to provide something rather than nothing.
     */
    public static final String RECIPE_AUTO = "auto";

    /** Tenant-overridable setting that names the recipe to spawn when
     *  the selector returns NONE for a goal-only spawn (selector-routed
     *  mode). Empty value disables the fallback entirely — caller then
     *  sees the bare NONE result and decides what to do. See
     *  {@code specification/recipe-routing.md} §6. */
    static final String SETTING_FALLBACK_RECIPE = "routing.fallback.recipe";

    /** Default value for {@link #SETTING_FALLBACK_RECIPE} when no
     *  tenant override is set. Hactar can generate a JavaScript handler
     *  for arbitrary goals — the most flexible default fallback. */
    static final String DEFAULT_FALLBACK_RECIPE = "hactar";

    /** Recipe used when the selector returns NONE and no trigger was
     *  observed in the goal. This is the standard non-specialty path:
     *  no trigger → ford via the bundled {@code default} recipe.
     *  Not configurable per tenant — tenants override the
     *  {@code default} recipe document itself via the cascade. */
    static final String DEFAULT_RECIPE = "default";

    /** Cap on the "Did you mean" suggestion list — keep the error compact. */
    private static final int SUGGESTION_LIMIT = 5;

    /** Levenshtein cutoff above which a candidate is not a "close" match. */
    private static final int CLOSE_MATCH_DISTANCE = 5;

    private static final Map<String, Object> SCHEMA;

    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "Stable process name, unique per session."));
        properties.put("goal", Map.of(
                "type", "string",
                "description", "What the spawned process should accomplish. "
                        + "Used by engines that surface a goal (Marvin, "
                        + "Vogon, Slartibartfast) AND — when neither "
                        + "`recipe` nor `engine` is supplied — by the "
                        + "selector to pick a matching project recipe. "
                        + "Be specific: 'write a research report on gRPC "
                        + "vs REST' is better than 'do something with "
                        + "research'. The field `prompt` is also accepted "
                        + "as an alias if you only pass that (tolerance "
                        + "for the arthur_action DELEGATE shape)."));
        properties.put("recipe", Map.of(
                "type", "string",
                "description", "Preferred routing: recipe name for cascade "
                        + "resolution. Empty / null / 'auto' → the "
                        + "selector picks a recipe from `goal`. Unknown "
                        + "names fail strict with a suggestion list — "
                        + "consult `recipe_list` if unsure."));
        properties.put("engine", Map.of(
                "type", "string",
                "description", "Direct-engine path: engine name "
                        + "(e.g. 'ford'). Ignored when `recipe` is set. "
                        + "Only useful when you need a specific engine "
                        + "WITHOUT recipe defaults; otherwise leave "
                        + "empty and let the selector route."));
        properties.put("title", Map.of(
                "type", "string",
                "description", "Optional human-readable title."));
        properties.put("steerContent", Map.of(
                "type", "string",
                "description", "Optional initial USER_CHAT_INPUT to push into the "
                        + "fresh process's pending queue right after start. "
                        + "Equivalent to a `process_steer` immediately after "
                        + "`process_create` — saves a tool round-trip and "
                        + "removes the trap of forgetting the steer for "
                        + "Ford-style workers (which would otherwise sit IDLE "
                        + "with no goal). For Marvin / Vogon engines (async, "
                        + "task-tree driven from `goal`) this is usually "
                        + "redundant — pass `goal` instead."));
        properties.put("params", Map.of(
                "type", "object",
                "description", "Engine-specific runtime parameters "
                        + "(model, validation, maxIterations, …). With a recipe, "
                        + "these override the recipe's defaults per-key.",
                "additionalProperties", true));
        properties.put("fallbackOnNone", Map.of(
                "type", "boolean",
                "description", "Selector-routed mode only: when the selector "
                        + "returns NONE (no recipe-name match and no trigger "
                        + "keyword hit), spawn the tenant-configured fallback "
                        + "recipe (`routing.fallback.recipe`, default hactar) "
                        + "so the user's goal still gets handled. Default "
                        + "true. Set false to receive the bare NONE result "
                        + "and decide explicitly."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("name", "goal"));
    }

    private final ThinkProcessService thinkProcessService;
    private final de.mhus.vance.brain.inherit.ParentContextSpawnHelper parentContextSpawnHelper;
    /**
     * Lazy because the bean graph cycles otherwise:
     * {@code ThinkEngineService → ToolDispatcher → BuiltInToolSource → this}.
     * Resolving via {@link ObjectProvider} defers the lookup to
     * first use, by which time the singleton is built.
     */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final RecipeResolver recipeResolver;
    private final RecipeLoader recipeLoader;
    private final RecipeSelectorService selector;
    private final SettingService settingService;
    /** Same lazy-lookup reasoning — router pulls in EngineWsClient + emitter. */
    private final ObjectProvider<EngineMessageRouter> messageRouterProvider;

    public ProcessCreateTool(
            ThinkProcessService thinkProcessService,
            ObjectProvider<ThinkEngineService> thinkEngineServiceProvider,
            RecipeResolver recipeResolver,
            RecipeLoader recipeLoader,
            RecipeSelectorService selector,
            SettingService settingService,
            ObjectProvider<EngineMessageRouter> messageRouterProvider,
            de.mhus.vance.brain.inherit.ParentContextSpawnHelper parentContextSpawnHelper) {
        this.thinkProcessService = thinkProcessService;
        this.thinkEngineServiceProvider = thinkEngineServiceProvider;
        this.recipeResolver = recipeResolver;
        this.recipeLoader = recipeLoader;
        this.selector = selector;
        this.settingService = settingService;
        this.messageRouterProvider = messageRouterProvider;
        this.parentContextSpawnHelper = parentContextSpawnHelper;
    }

    @Override
    public String name() {
        return "process_create";
    }

    @Override
    public String description() {
        return "Create a new think-process in the current session and "
                + "start its engine. Three routing modes: (a) pass "
                + "`recipe` to spawn a known recipe directly — unknown "
                + "names fail strict with a suggestion list so you can "
                + "retry with a fixed name. (b) pass `engine` for a "
                + "direct-engine spawn without recipe defaults. (c) "
                + "leave both empty so the trigger-gated selector "
                + "picks a recipe from `goal` (deterministic recipe-"
                + "name / engine-name / trigger-keyword pre-check; LLM "
                + "only on multi-trigger ambiguity). On NONE the "
                + "tenant fallback recipe (`routing.fallback.recipe`, "
                + "default hactar) is spawned so the goal still gets "
                + "handled. "
                + "Modes (a)/(b) return {name, status, engine, recipe?, "
                + "steered?}. Mode (c) returns {decision, recipe, "
                + "engine, rationale, fallback?, process?} — the "
                + "spawn metadata is nested under `process` on a MATCH "
                + "outcome or when the fallback recipe was spawned. "
                + "Pass `steerContent` to atomically push an initial "
                + "USER_CHAT_INPUT into the new process's pending queue.";
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
    public java.util.Set<String> labels() {
        return java.util.Set.of("executive");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String sessionId = ctx.sessionId();
        if (sessionId == null) {
            throw new ToolException("process_create requires a session scope");
        }
        String name = stringOrThrow(params, "name");
        // Schema-drift tolerance: weaker tool-use models (Gemma-4-mlx,
        // gpt-oss-20b) confuse this tool's `goal` with Arthur's
        // arthur_action(DELEGATE) field `prompt`, sending only `prompt`
        // on the first attempt. Treat `prompt` as an alias for `goal`
        // when `goal` is missing — same idea, just the field the model
        // happened to pick. Logged so prompt-drift stays visible.
        String goal = optString(params, "goal");
        if (goal == null) {
            String aliasPrompt = optString(params, "prompt");
            if (aliasPrompt != null) {
                log.warn("process_create called without 'goal' but with 'prompt' "
                                + "(name='{}') — using prompt as goal (schema-drift "
                                + "tolerance for weak tool-use models).",
                        name);
                goal = aliasPrompt;
            }
        }
        if (goal == null) {
            throw new ToolException("'goal' is required and must be a non-empty "
                    + "string (describes what the spawned process should "
                    + "accomplish). For an additional initial USER message, "
                    + "use 'steerContent'.");
        }
        String recipeName = normaliseRecipeParam(optString(params, "recipe"));
        String engineName = optString(params, "engine");
        String title = optString(params, "title");
        String steerContent = optString(params, "steerContent");
        Map<String, Object> callerParams = optMap(params, "params");
        boolean fallbackOnNone = optBoolean(params, "fallbackOnNone", true);

        if (recipeName != null && engineName != null) {
            log.info("process_create called with both recipe='{}' and engine='{}' — recipe wins",
                    recipeName, engineName);
            engineName = null;
        }

        // Selector-routed mode: no explicit recipe and no explicit engine
        // means the caller is asking us to route. Run the trigger-gated
        // selector. On NONE, spawn the tenant fallback recipe so the
        // goal isn't dropped. The actual spawn re-enters this tool with
        // the chosen recipe, so all three paths share one code path.
        if (recipeName == null && engineName == null) {
            return invokeSelectorRouted(
                    ctx, name, goal, title, steerContent, callerParams,
                    fallbackOnNone);
        }

        // Inherit the connection-profile from the parent process so a worker
        // spawned by an Arthur running on a foot connection picks up the
        // foot profile-block. Falls back to null when the parent has no
        // profile (e.g. service-spawned processes).
        String parentProfile = ctx.processId() == null ? null
                : thinkProcessService.findById(ctx.processId())
                        .map(ThinkProcessDocument::getConnectionProfile)
                        .orElse(null);

        Optional<AppliedRecipe> applied;
        try {
            applied = recipeResolver.applyDefaulting(
                    ctx.tenantId(), ctx.projectId(),
                    recipeName, engineName, parentProfile, callerParams);
        } catch (RecipeResolver.UnknownRecipeException ure) {
            // Strict mode: report the bad name + a "did you mean" list
            // so the LLM corrects on retry rather than wandering.
            throw new ToolException(
                    buildUnknownRecipeMessage(recipeName, ctx.tenantId(), ctx.projectId()));
        } catch (RecipeResolver.UnknownEngineException uee) {
            throw new ToolException(uee.getMessage());
        }

        ThinkProcessDocument fresh;
        try {
            if (applied.isPresent()) {
                fresh = createFromRecipeApplied(
                        ctx, sessionId, name, title, goal, applied.get());
            } else {
                fresh = createFromEngine(
                        ctx, sessionId, name, engineName, title, goal, callerParams);
            }
        } catch (ThinkProcessService.ThinkProcessAlreadyExistsException e) {
            // Name collision: don't fail the LLM-loop with an error
            // tool-result — weak models then respawn under tweaked
            // names and we end up with parallel workers on the same
            // task (the gemma4-mlx loop we observed). Return a
            // structured hint that names the existing process,
            // its status and engine, plus the two valid next moves
            // (steer it / pick another name). See
            // planning/arthur-process-event-attribution.md.
            return buildAlreadyExistsHint(ctx, name);
        }

        try {
            thinkEngineServiceProvider.getObject().start(fresh);
        } catch (RuntimeException e) {
            throw new ToolException(
                    "Engine start failed for '" + name + "': " + e.getMessage(), e);
        }

        // Worker-spawn-context pre-paste: if the recipe declares
        // `inheritContext` (other than `none`), render the spawning
        // process's chat history as a Markdown block and prepend it to
        // the initial steer. The worker then sees its task with the
        // parent's conversation already in view — no need to pull via
        // process_history_text in the common case. See
        // planning/worker-spawn-context.md.
        String effectiveSteer = applyInheritContext(
                ctx, applied.orElse(null), fresh, steerContent);

        boolean steered = pushInitialSteer(ctx, fresh, name, effectiveSteer);
        return buildSpawnResult(fresh, effectiveSteer, steered);
    }

    /**
     * Wraps {@code steerContent} with a {@code ## Parent context} block
     * via the centralised {@link de.mhus.vance.brain.inherit.ParentContextSpawnHelper}.
     * Same helper backs other spawn paths (Marvin's CALL_RECIPE drive),
     * so the wrap shape stays uniform across the codebase.
     */
    private @Nullable String applyInheritContext(
            ToolInvocationContext ctx,
            @Nullable AppliedRecipe applied,
            ThinkProcessDocument fresh,
            @Nullable String steerContent) {
        String levelRaw = null;
        if (applied != null && applied.params() != null) {
            Object v = applied.params().get("inheritContext");
            if (v instanceof String s) levelRaw = s;
        }
        try {
            return parentContextSpawnHelper.wrap(levelRaw, ctx.processId(), steerContent);
        } catch (RuntimeException e) {
            log.warn("process_create id='{}' inheritContext wrap failed: {}",
                    fresh.getId(), e.toString());
            return steerContent;
        }
    }

    /**
     * Run the trigger-gated selector and, on NONE, spawn the tenant
     * fallback recipe. Re-enters {@link #invoke} via {@link #spawnAndReturn}
     * so the actual spawn shares one code path with explicit-recipe
     * mode. The fallback recipe comes from setting
     * {@link #SETTING_FALLBACK_RECIPE} (default {@link #DEFAULT_FALLBACK_RECIPE}).
     * Empty setting value disables the fallback — the tool then returns
     * the bare NONE result.
     */
    private Map<String, Object> invokeSelectorRouted(
            ToolInvocationContext ctx, String name, String goal,
            @Nullable String title, @Nullable String steerContent,
            @Nullable Map<String, Object> callerParams,
            boolean fallbackOnNone) {
        ThinkProcessDocument caller = resolveCaller(ctx);
        RecipeSelectorService.Result result = selector.select(caller, goal);

        // Selector-routed output shape: selector decision / recipe /
        // engine / rationale at top level (so callers + tests don't
        // have to dig); spawn metadata nested under `process` when an
        // actual spawn happened. Mirrors the former
        // process_create_delegate surface so existing consumers keep
        // working.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decision", result.decision().name());
        out.put("recipe", result.recipeName());
        out.put("engine", result.engineName());
        out.put("rationale", result.rationale());

        if (result.decision() == RecipeSelectorService.Result.Decision.MATCH) {
            return spawnAndReturn(out, ctx, name, goal, result.recipeName(),
                    title, steerContent, callerParams);
        }

        // NONE splits into two cases (see specification/recipe-routing.md):
        //   * triggerObserved=false: the goal contained no trigger
        //     at all. Spawn the standard `default` recipe (ford in
        //     bundled defaults) — that is the regular non-specialty
        //     path, not the configurable fallback.
        //   * triggerObserved=true: a trigger fired but no candidate
        //     matched after LLM disambiguation. Spawn the configurable
        //     fallback recipe (routing.fallback.recipe, default hactar).
        if (!fallbackOnNone) {
            log.info("process_create name='{}' goal='{}' → NONE (fallbackOnNone=false): {}",
                    name, abbrev(goal, 80), result.rationale());
            return out;
        }
        if (!result.triggerObserved()) {
            log.info("process_create name='{}' → NONE without trigger, "
                            + "falling through to default recipe (rationale: {})",
                    name, result.rationale());
            Map<String, Object> fallbackInfo = new LinkedHashMap<>();
            fallbackInfo.put("recipe", DEFAULT_RECIPE);
            fallbackInfo.put("source", "default-recipe (no trigger)");
            out.put("fallback", fallbackInfo);
            return spawnAndReturn(out, ctx, name, goal, DEFAULT_RECIPE,
                    title, steerContent, callerParams);
        }

        // Trigger seen but no concrete match — consult the setting.
        String fallbackRecipe = resolveFallbackRecipe(ctx);
        if (fallbackRecipe == null) {
            log.info("process_create name='{}' → NONE after trigger, "
                            + "fallback disabled by setting: {}",
                    name, result.rationale());
            return out;
        }
        log.info("process_create name='{}' → NONE after trigger, "
                        + "spawning fallback recipe '{}' (rationale: {})",
                name, fallbackRecipe, result.rationale());
        Map<String, Object> fallbackInfo = new LinkedHashMap<>();
        fallbackInfo.put("recipe", fallbackRecipe);
        fallbackInfo.put("source", SETTING_FALLBACK_RECIPE);
        out.put("fallback", fallbackInfo);

        return spawnAndReturn(out, ctx, name, goal, fallbackRecipe,
                title, steerContent, callerParams);
    }

    /**
     * Reads {@link #SETTING_FALLBACK_RECIPE} via the cascade. Empty
     * string disables the fallback (returns {@code null} so the caller
     * doesn't spawn). Missing value falls through to
     * {@link #DEFAULT_FALLBACK_RECIPE}.
     */
    private @Nullable String resolveFallbackRecipe(ToolInvocationContext ctx) {
        String configured = settingService.getStringValueCascade(
                ctx.tenantId(), ctx.projectId(), /*processId*/ null,
                SETTING_FALLBACK_RECIPE);
        if (configured == null) return DEFAULT_FALLBACK_RECIPE;
        String trimmed = configured.trim();
        if (trimmed.isEmpty()) return null;     // explicit opt-out
        return trimmed;
    }

    /**
     * Re-enter {@link #invoke} with the chosen (or Slart-generated)
     * recipe so the actual spawn shares one code path, then nest the
     * spawn result under {@code process} so the selector-routed
     * caller still sees its decision/recipe/rationale at the top
     * level.
     */
    private Map<String, Object> spawnAndReturn(
            Map<String, Object> out,
            ToolInvocationContext ctx, String name, String goal,
            String recipe, @Nullable String title,
            @Nullable String steerContent,
            @Nullable Map<String, Object> callerParams) {
        Map<String, Object> innerParams = new LinkedHashMap<>();
        innerParams.put("name", name);
        innerParams.put("goal", goal);
        innerParams.put("recipe", recipe);
        if (title != null) innerParams.put("title", title);
        if (steerContent != null) innerParams.put("steerContent", steerContent);
        if (callerParams != null) innerParams.put("params", callerParams);
        Map<String, Object> spawnResult = invoke(innerParams, ctx);
        out.put("process", spawnResult);
        log.info("process_create name='{}' → spawned recipe='{}' via selector",
                name, recipe);
        return out;
    }

    /**
     * Compose a {@link ToolException} message that names the bad
     * recipe, lists the closest matches (Levenshtein), and prints the
     * full available-recipes inventory. Strict path — caller (LLM)
     * sees the error as a tool-result and corrects on the next turn.
     */
    private String buildUnknownRecipeMessage(
            String requested, String tenantId, @Nullable String projectId) {
        List<String> all = recipeLoader.listAll(tenantId, projectId).stream()
                .map(ResolvedRecipe::name)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .distinct()
                .toList();
        if (all.isEmpty()) {
            return "Unknown recipe '" + requested + "'. "
                    + "No recipes are loaded in this project — "
                    + "omit `recipe` to let the selector route from `goal`.";
        }
        List<String> suggestions = closeMatches(requested, all);
        StringBuilder sb = new StringBuilder()
                .append("Unknown recipe '").append(requested).append("'. ");
        if (!suggestions.isEmpty()) {
            sb.append("Did you mean: ")
                    .append(String.join(", ", suggestions))
                    .append("? ");
        }
        sb.append("Available: ")
                .append(String.join(", ", all))
                .append(". Use `recipe_list` for descriptions, or omit ")
                .append("`recipe` to let the selector pick from `goal`.");
        return sb.toString();
    }

    /**
     * Edit-distance ranking. Lower-case both sides so casing
     * differences are zero-cost; cap by {@link #CLOSE_MATCH_DISTANCE}
     * so an unrelated typo doesn't trigger a misleading hint.
     */
    static List<String> closeMatches(String requested, List<String> candidates) {
        String needle = requested == null ? "" : requested.toLowerCase(Locale.ROOT).trim();
        if (needle.isEmpty() || candidates.isEmpty()) return List.of();
        List<String[]> ranked = new ArrayList<>(candidates.size());
        for (String c : candidates) {
            int d = levenshtein(needle, c.toLowerCase(Locale.ROOT));
            if (d <= CLOSE_MATCH_DISTANCE) {
                ranked.add(new String[]{c, Integer.toString(d)});
            }
        }
        ranked.sort(Comparator.comparingInt(a -> Integer.parseInt(a[1])));
        return ranked.stream()
                .limit(SUGGESTION_LIMIT)
                .map(a -> a[0])
                .toList();
    }

    /** Two-row Levenshtein — fine for short recipe names. */
    private static int levenshtein(String a, String b) {
        if (a.equals(b)) return 0;
        int n = a.length();
        int m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[m];
    }

    private boolean pushInitialSteer(
            ToolInvocationContext ctx, ThinkProcessDocument fresh,
            String name, @Nullable String steerContent) {
        if (steerContent == null || steerContent.isBlank()) return false;
        PendingMessageDocument msg = PendingMessageDocument.builder()
                .type(PendingMessageType.USER_CHAT_INPUT)
                .at(Instant.now())
                .fromUser(ctx.processId() == null
                        ? null : "process:" + ctx.processId())
                .content(steerContent)
                .build();
        boolean steered = messageRouterProvider.getObject()
                .dispatch(ctx.processId(), fresh.getId(), msg);
        if (!steered) {
            log.warn("process_create: steerContent dispatch failed for '{}' (id='{}')",
                    name, fresh.getId());
        }
        return steered;
    }

    /**
     * Output shape for the explicit-recipe / direct-engine spawn
     * path — same surface the former standalone {@code process_create}
     * tool returned: name, status, engine, engineVersion, recipe?,
     * steered?. The selector-routed path nests this under its own
     * {@code process} key (see {@link #spawnAndReturn}).
     */
    private Map<String, Object> buildSpawnResult(
            ThinkProcessDocument fresh, @Nullable String steerContent,
            boolean steered) {
        ThinkProcessDocument refreshed = thinkProcessService.findById(fresh.getId())
                .orElse(fresh);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", refreshed.getName());
        out.put("status", refreshed.getStatus() == null ? null : refreshed.getStatus().name());
        out.put("engine", refreshed.getThinkEngine());
        out.put("engineVersion", refreshed.getThinkEngineVersion());
        if (refreshed.getRecipeName() != null) {
            out.put("recipe", refreshed.getRecipeName());
        }
        if (steerContent != null) {
            out.put("steered", steered);
        }
        return out;
    }

    /**
     * Structured tool-result for the "name already in session" case
     * — non-fatal hint instead of an error. Tells the LLM what's
     * there and what to do, so weak tool-use models don't loop with
     * tweaked names. See planning/arthur-process-event-attribution.md.
     */
    private Map<String, Object> buildAlreadyExistsHint(
            ToolInvocationContext ctx, String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "already_exists");
        out.put("name", name);
        thinkProcessService.findByName(ctx.tenantId(), ctx.sessionId(), name)
                .ifPresent(existing -> {
                    if (existing.getStatus() != null) {
                        out.put("existingStatus", existing.getStatus().name());
                    }
                    if (existing.getThinkEngine() != null) {
                        out.put("existingEngine", existing.getThinkEngine());
                    }
                    if (existing.getRecipeName() != null) {
                        out.put("existingRecipe", existing.getRecipeName());
                    }
                });
        out.put("hint", "A process with this name already exists in the "
                + "current session. To send the same goal as additional "
                + "input to the existing process, call `process_steer(name=\""
                + name + "\", content=…)`. To run a SECOND process in "
                + "parallel on a similar topic, retry `process_create` "
                + "with a different `name`. Do NOT silently retry with the "
                + "same name — the original spawn already succeeded.");
        log.info("process_create name='{}' rejected as already_exists — returning hint",
                name);
        return out;
    }

    private ThinkProcessDocument createFromRecipeApplied(
            ToolInvocationContext ctx, String sessionId, String name,
            String title, String goal, AppliedRecipe applied) {
        ThinkEngine engine = thinkEngineServiceProvider.getObject().resolve(applied.engine())
                .orElseThrow(() -> new ToolException(
                        "Recipe '" + applied.name() + "' references unknown engine '"
                                + applied.engine() + "'"));
        return thinkProcessService.create(
                ctx.tenantId(), ctx.projectId(), sessionId, name,
                engine.name(), engine.version(), title, goal,
                /*parentProcessId*/ ctx.processId(),
                applied.params(),
                applied.name(),
                applied.promptOverride(),
                applied.promptOverrideAppend(),
                applied.promptMode(),
                applied.dataRelayCorrection(),
                applied.effectiveAllowedTools(),
                applied.connectionProfile(),
                applied.defaultActiveSkills(),
                applied.allowedSkills() == null
                        ? null : Set.copyOf(applied.allowedSkills()));
    }

    private ThinkProcessDocument createFromEngine(
            ToolInvocationContext ctx, String sessionId, String name,
            String engineName, String title, String goal,
            @Nullable Map<String, Object> callerParams) {
        ThinkEngine engine = thinkEngineServiceProvider.getObject().resolve(engineName)
                .orElseThrow(() -> new ToolException(
                        "Unknown engine '" + engineName + "' — known: "
                                + thinkEngineServiceProvider.getObject().listEngines()));
        return thinkProcessService.create(
                ctx.tenantId(), ctx.projectId(), sessionId, name,
                engine.name(), engine.version(), title, goal,
                /*parentProcessId*/ ctx.processId(),
                callerParams);
    }

    private ThinkProcessDocument resolveCaller(ToolInvocationContext ctx) {
        String pid = ctx.processId();
        if (pid == null || pid.isBlank()) {
            throw new ToolException(
                    "process_create in selector-routed mode must be invoked "
                            + "from a running process (no processId in context)");
        }
        return thinkProcessService.findById(pid)
                .orElseThrow(() -> new ToolException(
                        "calling process '" + pid + "' not found"));
    }

    /** "auto"/empty/null → null (selector mode); otherwise trimmed value. */
    private static @Nullable String normaliseRecipeParam(@Nullable String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (RECIPE_AUTO.equalsIgnoreCase(trimmed)) return null;
        return trimmed;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    private static @Nullable String optString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static @Nullable Map<String, Object> optMap(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return null;
    }

    private static boolean optBoolean(Map<String, Object> params, String key, boolean defaultValue) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) return true;
            if ("false".equalsIgnoreCase(s.trim())) return false;
        }
        return defaultValue;
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
