package de.mhus.vance.brain.tools.process;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.action.ActionExecutorRegistry;
import de.mhus.vance.brain.action.ActionOutcome;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.brain.delegate.RecipeSelectorService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Spawns a new think-process inside the current session. Smart router
 * over the central {@link ActionExecutorRegistry} — handles three input
 * shapes, then dispatches a {@link TriggerAction.Recipe}.
 *
 * <ul>
 *   <li><b>Explicit recipe.</b> {@code recipe="analyze"} → cascade-resolve
 *       via the executor. Unknown recipe names fail strict with a
 *       Levenshtein-suggestion list embedded in the {@code ToolException}
 *       message — the LLM corrects on the next turn.</li>
 *   <li><b>Direct-engine.</b> {@code recipe} omitted (or "auto"),
 *       {@code engine="ford"} → engine-direct spawn without recipe
 *       defaults.</li>
 *   <li><b>Selector-routed.</b> Both omitted → {@link RecipeSelectorService}
 *       runs the trigger-gated pre-check on {@code goal}. A
 *       deterministic match spawns the matched recipe; NONE falls back
 *       to the configured tenant default ({@code routing.fallback.recipe},
 *       default {@code slart-and-run}) or, when no trigger was observed,
 *       to the bundled {@code default} recipe.</li>
 * </ul>
 *
 * <p>The selector decision (decision / recipe / engine / rationale /
 * fallback?) wraps the spawn metadata under the {@code process} key —
 * mirrors the former {@code process_create_delegate} surface so existing
 * consumers keep working.
 *
 * <p>{@code steerContent} flows through {@code action.initialMessage}.
 * The executor wraps it with the parent's chat-history (via
 * {@code ParentContextSpawnHelper}) when {@code recipe.inheritContext}
 * is set, then pushes the wrapped message as USER_CHAT_INPUT.
 *
 * <p>If both {@code recipe} and {@code engine} are set, {@code recipe}
 * wins and {@code engine} is logged-and-ignored — easier to reason about
 * than failing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@de.mhus.vance.toolpack.SpawnTool
public class ProcessCreateTool implements Tool {

    /**
     * Value treated as "no explicit recipe" — same outcome as omitting
     * the param entirely. Lets callers pass a literal sentinel when
     * their template needs to provide something rather than nothing.
     */
    public static final String RECIPE_AUTO = "auto";

    /**
     * Tenant-overridable setting that names the recipe to spawn when the
     * selector returns NONE for a goal-only spawn (selector-routed
     * mode). Empty value disables the fallback entirely — caller then
     * sees the bare NONE result.
     */
    static final String SETTING_FALLBACK_RECIPE = "routing.fallback.recipe";

    /** Default value for {@link #SETTING_FALLBACK_RECIPE}. */
    static final String DEFAULT_FALLBACK_RECIPE = "slart-and-run";

    /**
     * Recipe used when the selector returns NONE and no trigger was
     * observed in the goal. Not configurable per tenant — tenants
     * override the {@code default} recipe document itself via the
     * cascade.
     */
    static final String DEFAULT_RECIPE = "default";

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
                "description", "Optional initial USER_CHAT_INPUT to push "
                        + "into the fresh process's pending queue right "
                        + "after start. Equivalent to a `process_steer` "
                        + "immediately after `process_create` — saves a "
                        + "tool round-trip and removes the trap of "
                        + "forgetting the steer for Ford-style workers."));
        properties.put("params", Map.of(
                "type", "object",
                "description", "Engine-specific runtime parameters "
                        + "(model, validation, maxIterations, …). With a "
                        + "recipe, these override the recipe's defaults "
                        + "per-key.",
                "additionalProperties", true));
        properties.put("fallbackOnNone", Map.of(
                "type", "boolean",
                "description", "Selector-routed mode only: when the "
                        + "selector returns NONE, spawn the tenant-"
                        + "configured fallback recipe (default "
                        + "slart-and-run). Default true."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("name", "goal"));
    }

    private final ActionExecutorRegistry actionRegistry;
    private final ThinkProcessService thinkProcessService;
    private final RecipeSelectorService selector;
    private final SettingService settingService;

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
                + "leave both empty so the trigger-gated selector picks "
                + "a recipe from `goal`. On NONE the tenant fallback "
                + "recipe (`routing.fallback.recipe`, default "
                + "slart-and-run) is spawned. Modes (a)/(b) return "
                + "{name, status, engine, recipe?, steered?}. Mode (c) "
                + "returns {decision, recipe, engine, rationale, "
                + "fallback?, process?} — the spawn metadata is nested "
                + "under `process` on a MATCH or when the fallback "
                + "recipe was spawned. Pass `steerContent` to atomically "
                + "push an initial USER_CHAT_INPUT into the new process's "
                + "pending queue.";
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
    public Set<String> labels() {
        return Set.of("executive");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.sessionId() == null) {
            throw new ToolException("process_create requires a session scope");
        }
        String name = stringOrThrow(params, "name");
        String goal = resolveGoalWithPromptAlias(params, name);
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

        // ── Selector-routed mode (decide recipe before dispatch) ─────────
        if (recipeName == null && engineName == null) {
            return invokeSelectorRouted(
                    ctx, name, goal, title, steerContent, callerParams, fallbackOnNone);
        }

        // ── Explicit recipe / direct-engine dispatch ─────────────────────
        return dispatch(ctx, name, title, goal, recipeName, engineName,
                steerContent, callerParams);
    }

    /**
     * Run the trigger-gated selector. On {@code MATCH}, dispatch the
     * chosen recipe. On {@code NONE}, apply the fallback cascade
     * (default recipe when no trigger, configured fallback recipe when
     * a trigger fired but no candidate matched). The selector decision
     * always travels at the top of the response; the spawn metadata
     * (when any) nests under {@code process}.
     */
    private Map<String, Object> invokeSelectorRouted(
            ToolInvocationContext ctx, String name, String goal,
            @Nullable String title, @Nullable String steerContent,
            @Nullable Map<String, Object> callerParams,
            boolean fallbackOnNone) {
        ThinkProcessDocument caller = resolveCaller(ctx);
        RecipeSelectorService.Result result = selector.select(caller, goal);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decision", result.decision().name());
        out.put("recipe", result.recipeName());
        out.put("engine", result.engineName());
        out.put("rationale", result.rationale());

        if (result.decision() == RecipeSelectorService.Result.Decision.MATCH) {
            Map<String, Object> spawn = dispatch(ctx, name, title, goal,
                    result.recipeName(), /*engine*/ null, steerContent, callerParams);
            out.put("process", spawn);
            log.info("process_create name='{}' → spawned recipe='{}' via selector",
                    name, result.recipeName());
            return out;
        }

        if (!fallbackOnNone) {
            log.info("process_create name='{}' goal='{}' → NONE (fallbackOnNone=false): {}",
                    name, abbrev(goal, 80), result.rationale());
            return out;
        }

        // NONE: split on triggerObserved.
        if (!result.triggerObserved()) {
            log.info("process_create name='{}' → NONE without trigger, "
                            + "falling through to default recipe (rationale: {})",
                    name, result.rationale());
            Map<String, Object> fallbackInfo = new LinkedHashMap<>();
            fallbackInfo.put("recipe", DEFAULT_RECIPE);
            fallbackInfo.put("source", "default-recipe (no trigger)");
            out.put("fallback", fallbackInfo);
            Map<String, Object> spawn = dispatch(ctx, name, title, goal,
                    DEFAULT_RECIPE, /*engine*/ null, steerContent, callerParams);
            out.put("process", spawn);
            return out;
        }

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

        Map<String, Object> spawn = dispatch(ctx, name, title, goal,
                fallbackRecipe, /*engine*/ null, steerContent, callerParams);
        out.put("process", spawn);
        return out;
    }

    /**
     * Build a {@link TriggerAction.Recipe} from the resolved arguments
     * and dispatch it through {@link ActionExecutorRegistry}. Maps the
     * executor's outcome to the tool's output shape, throwing a rich
     * {@link ToolException} for unknown-recipe and already-exists is
     * returned as the structured hint that the LLM can act on.
     */
    private Map<String, Object> dispatch(
            ToolInvocationContext ctx, String name, @Nullable String title,
            String goal, @Nullable String recipeName, @Nullable String engineName,
            @Nullable String steerContent, @Nullable Map<String, Object> callerParams) {
        String parentProfile = parentConnectionProfile(ctx.processId());
        TriggerAction.Recipe action = new TriggerAction.Recipe(
                recipeName,
                engineName,
                name,
                title,
                goal,
                /*inheritContextLevel*/ null,  // executor reads from recipe.params
                parentProfile,
                steerContent,
                callerParams,
                /*runAs*/ null);
        TriggerContext triggerCtx = new TriggerContext(
                ctx.tenantId(), ctx.projectId(),
                /*resolvedRunAs*/ null, /*correlationId*/ null,
                /*sourceTag*/ "tool:process_create",
                ctx.sessionId(), ctx.processId());

        ActionResult result = actionRegistry.execute(action, triggerCtx, TriggerKind.TOOL);
        return mapResultToToolOutput(result, recipeName, ctx.tenantId(), ctx.projectId());
    }

    /**
     * Maps the {@link ActionResult} to the legacy tool-output shape:
     * <ul>
     *   <li>{@link ActionOutcome#SCHEDULED} → spawn-metadata map.</li>
     *   <li>{@link ActionOutcome#SUCCESS} → already-exists hint (passes
     *       through {@code result.output()} verbatim — soft idempotent).</li>
     *   <li>Unknown-recipe failure → {@link ToolException} with a
     *       composed "Did you mean …" + "Available …" message.</li>
     *   <li>Any other failure → {@link ToolException} with the raw
     *       executor message.</li>
     * </ul>
     */
    private Map<String, Object> mapResultToToolOutput(
            ActionResult result, @Nullable String requestedRecipe,
            String tenantId, @Nullable String projectId) {
        switch (result.outcome()) {
            case SCHEDULED -> {
                Map<String, Object> out = result.output();
                if (out == null) {
                    return Map.of("processId", result.spawnedId());
                }
                return out;
            }
            case SUCCESS -> {
                // already_exists soft-success.
                Map<String, Object> out = result.output();
                return out != null ? out : Map.of("status", "already_exists");
            }
            case TECHNICAL_ERROR, BUSINESS_ERROR, TIMEOUT, PERMISSION_ERROR, CANCELLED -> {
                String msg = result.errorMessage() == null
                        ? "process_create failed" : result.errorMessage();
                Map<String, Object> output = result.output();
                if (output != null && "unknown recipe ".regionMatches(0, msg, 0,
                        Math.min(msg.length(), "unknown recipe ".length()))) {
                    throw new ToolException(composeUnknownRecipeMessage(
                            requestedRecipe, output));
                }
                throw new ToolException("process_create: " + msg);
            }
        }
        throw new ToolException("process_create: unexpected outcome " + result.outcome());
    }

    /**
     * Compose the strict-mode "Did you mean …" message from the
     * {@code suggestions}/{@code available} that the executor builds.
     * Keeps the LLM-recovery experience identical to the pre-rewrite
     * shape.
     */
    private static String composeUnknownRecipeMessage(
            @Nullable String requested, Map<String, Object> output) {
        @SuppressWarnings("unchecked")
        List<String> suggestions = (List<String>) output.getOrDefault("suggestions", List.of());
        @SuppressWarnings("unchecked")
        List<String> available = (List<String>) output.getOrDefault("available", List.of());
        StringBuilder sb = new StringBuilder()
                .append("Unknown recipe '").append(requested).append("'. ");
        if (available.isEmpty()) {
            sb.append("No recipes are loaded in this project — omit ")
                    .append("`recipe` to let the selector route from `goal`.");
            return sb.toString();
        }
        if (!suggestions.isEmpty()) {
            sb.append("Did you mean: ").append(String.join(", ", suggestions))
                    .append("? ");
        }
        sb.append("Available: ").append(String.join(", ", available))
                .append(". Use `recipe_list` for descriptions, or omit ")
                .append("`recipe` to let the selector pick from `goal`.");
        return sb.toString();
    }

    /**
     * Reads {@link #SETTING_FALLBACK_RECIPE} via the cascade. Empty
     * string disables the fallback (returns {@code null}); missing
     * value falls through to {@link #DEFAULT_FALLBACK_RECIPE}.
     */
    private @Nullable String resolveFallbackRecipe(ToolInvocationContext ctx) {
        String configured = settingService.getStringValueCascade(
                ctx.tenantId(), ctx.projectId(), /*processId*/ null,
                SETTING_FALLBACK_RECIPE);
        if (configured == null) return DEFAULT_FALLBACK_RECIPE;
        String trimmed = configured.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed;
    }

    /**
     * Schema-drift tolerance: weak tool-use models (Gemma-4-mlx,
     * gpt-oss-20b) confuse this tool's {@code goal} with Arthur's
     * {@code arthur_action(DELEGATE)} field {@code prompt}, sending only
     * {@code prompt} on the first attempt. Treat {@code prompt} as an
     * alias for {@code goal} when {@code goal} is missing — same idea,
     * just the field the model happened to pick.
     */
    private String resolveGoalWithPromptAlias(Map<String, Object> params, String name) {
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
        return goal;
    }

    private @Nullable String parentConnectionProfile(@Nullable String parentProcessId) {
        if (parentProcessId == null || parentProcessId.isBlank()) return null;
        return thinkProcessService.findById(parentProcessId)
                .map(ThinkProcessDocument::getConnectionProfile)
                .orElse(null);
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
            String trimmed = s.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(trimmed)) return true;
            if ("false".equals(trimmed)) return false;
        }
        return defaultValue;
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
