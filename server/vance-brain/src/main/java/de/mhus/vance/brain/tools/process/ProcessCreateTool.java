package de.mhus.vance.brain.tools.process;

import de.mhus.vance.brain.delegate.RecipeSelectorService;
import de.mhus.vance.brain.delegate.SlartibartfastFallback;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
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
 *       omitted → the {@link RecipeSelectorService} reads the project's
 *       recipe inventory and picks a match for the supplied
 *       {@code goal}. NONE falls through to a Slartibartfast spawn
 *       that generates a fresh recipe (unless {@code fallbackOnNone:
 *       false}). This is the "I know what I want done but not which
 *       recipe to call" entry — replaces the former
 *       {@code process_create_delegate} surface, which only differed in
 *       its strict requirement of a task description.</li>
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
                        + "research'."));
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
                        + "returns NONE (no existing recipe matches), "
                        + "automatically spawn Slartibartfast to generate a "
                        + "fresh recipe and spawn that recipe. Adds 60-180s "
                        + "to the tool round-trip when triggered. Default "
                        + "true. Set false to let the caller handle NONE."));
        properties.put("asyncFallback", Map.of(
                "type", "boolean",
                "description", "Selector-routed mode only: when async=true "
                        + "the tool spawns Slartibartfast and returns "
                        + "immediately with {outcome: PENDING, "
                        + "slartProcessId}; the caller observes Slart's "
                        + "terminal event and decides whether to spawn the "
                        + "generated recipe. Default false (sync + auto-spawn)."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("name", "goal"));
    }

    private final ThinkProcessService thinkProcessService;
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
    private final SlartibartfastFallback slartFallback;
    /** Same lazy-lookup reasoning — router pulls in EngineWsClient + emitter. */
    private final ObjectProvider<EngineMessageRouter> messageRouterProvider;

    public ProcessCreateTool(
            ThinkProcessService thinkProcessService,
            ObjectProvider<ThinkEngineService> thinkEngineServiceProvider,
            RecipeResolver recipeResolver,
            RecipeLoader recipeLoader,
            RecipeSelectorService selector,
            SlartibartfastFallback slartFallback,
            ObjectProvider<EngineMessageRouter> messageRouterProvider) {
        this.thinkProcessService = thinkProcessService;
        this.thinkEngineServiceProvider = thinkEngineServiceProvider;
        this.recipeResolver = recipeResolver;
        this.recipeLoader = recipeLoader;
        this.selector = selector;
        this.slartFallback = slartFallback;
        this.messageRouterProvider = messageRouterProvider;
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
                + "leave both empty so the system picks the best-"
                + "matching recipe from `goal` via the selector LLM, "
                + "with Slartibartfast fallback when nothing fits. "
                + "Modes (a)/(b) return {name, status, engine, recipe?, "
                + "steered?}. Mode (c) returns {decision, recipe, "
                + "engine, rationale, fallback?, process?} — the "
                + "spawn metadata is nested under `process` on a MATCH "
                + "or GENERATED outcome. Pass `steerContent` to "
                + "atomically push an initial USER_CHAT_INPUT into the "
                + "new process's pending queue.";
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
        String goal = stringOrThrow(params, "goal");
        String recipeName = normaliseRecipeParam(optString(params, "recipe"));
        String engineName = optString(params, "engine");
        String title = optString(params, "title");
        String steerContent = optString(params, "steerContent");
        Map<String, Object> callerParams = optMap(params, "params");
        boolean fallbackOnNone = optBoolean(params, "fallbackOnNone", true);
        boolean asyncFallback = optBoolean(params, "asyncFallback", false);

        if (recipeName != null && engineName != null) {
            log.info("process_create called with both recipe='{}' and engine='{}' — recipe wins",
                    recipeName, engineName);
            engineName = null;
        }

        // Selector-routed mode: no explicit recipe and no explicit engine
        // means the LLM is telling us "you decide". Route through the
        // selector + Slart fallback, then re-enter this tool with the
        // chosen recipe so the actual spawn shares one code path.
        if (recipeName == null && engineName == null) {
            return invokeSelectorRouted(
                    ctx, name, goal, title, steerContent, callerParams,
                    fallbackOnNone, asyncFallback);
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
            throw new ToolException(e.getMessage());
        }

        try {
            thinkEngineServiceProvider.getObject().start(fresh);
        } catch (RuntimeException e) {
            throw new ToolException(
                    "Engine start failed for '" + name + "': " + e.getMessage(), e);
        }

        boolean steered = pushInitialSteer(ctx, fresh, name, steerContent);
        return buildSpawnResult(fresh, steerContent, steered);
    }

    /**
     * Run the selector + optional Slart-fallback for the "you pick"
     * mode, then re-enter this tool with the chosen recipe via the
     * explicit-recipe path. Keeps one spawn code path — the only
     * thing the selector-mode adds is the routing decision and (in
     * the NONE case) a Slart spawn that produces the recipe before
     * the actual spawn happens.
     */
    private Map<String, Object> invokeSelectorRouted(
            ToolInvocationContext ctx, String name, String goal,
            @Nullable String title, @Nullable String steerContent,
            @Nullable Map<String, Object> callerParams,
            boolean fallbackOnNone, boolean asyncFallback) {
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

        // NONE path.
        if (!fallbackOnNone) {
            log.info("process_create name='{}' goal='{}' → NONE (no fallback): {}",
                    name, abbrev(goal, 80), result.rationale());
            return out;
        }

        log.info("process_create name='{}' → NONE, invoking Slart fallback "
                        + "({} mode, rationale: {})",
                name, asyncFallback ? "async" : "sync", result.rationale());
        SlartibartfastFallback.Result fb = asyncFallback
                ? slartFallback.invokeAsync(caller, goal, name)
                : slartFallback.invoke(caller, goal, name);

        Map<String, Object> fallbackInfo = new LinkedHashMap<>();
        fallbackInfo.put("outcome", fb.outcome().name());
        fallbackInfo.put("slartProcessId", fb.slartProcessId());
        fallbackInfo.put("recipeName", fb.recipeName());
        fallbackInfo.put("recipePath", fb.recipePath());
        fallbackInfo.put("rationale", fb.rationale());
        out.put("fallback", fallbackInfo);

        // Async mode never auto-spawns the generated recipe — caller
        // watches Slart's terminal event and decides. GENERATED is a
        // sync-mode outcome only.
        if (fb.outcome() != SlartibartfastFallback.Outcome.GENERATED) {
            log.info("process_create name='{}' fallback outcome={}: {}",
                    name, fb.outcome(), fb.rationale());
            return out;
        }

        return spawnAndReturn(out, ctx, name, goal, fb.recipeName(),
                title, steerContent, callerParams);
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
