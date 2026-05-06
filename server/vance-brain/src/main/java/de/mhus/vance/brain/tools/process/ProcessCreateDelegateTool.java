package de.mhus.vance.brain.tools.process;

import de.mhus.vance.brain.delegate.RecipeSelectorService;
import de.mhus.vance.brain.delegate.SlartibartfastFallback;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Companion tool to {@link ProcessCreateTool}. Use this when the
 * caller knows WHAT they want done in natural language but not which
 * project recipe implements it. The tool runs a synchronous selector
 * LLM call ({@link RecipeSelectorService}) over the project recipe
 * inventory, then either delegates to {@code ProcessCreateTool} with
 * the matched recipe or returns a {@code NONE} decision so the caller
 * can decide on a fallback (typically: spawn Slartibartfast to
 * generate a fresh recipe, or ask the user to clarify).
 *
 * <p>Decision matrix for the calling LLM:
 *
 * <ul>
 *   <li>You know the recipe name → {@code process_create}.</li>
 *   <li>You only know the task → {@code process_create_delegate}.</li>
 * </ul>
 *
 * <p>The selector NEVER spawns a process on its own. The actual
 * spawn (when it happens) goes through {@link ProcessCreateTool} so
 * recipe-cascade resolution, profile inheritance, engine start, and
 * optional initial steer all behave identically — the selector is
 * just a routing layer in front.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessCreateDelegateTool implements Tool {

    private static final Map<String, Object> SCHEMA;

    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "Stable process name, unique per session "
                        + "(same convention as process_create)."));
        properties.put("task", Map.of(
                "type", "string",
                "description", "Natural-language description of what "
                        + "should be done. The selector picks a "
                        + "matching project recipe based on this text "
                        + "and the engine catalog. Be specific: 'write a "
                        + "research report on gRPC vs REST' is better "
                        + "than 'do something with research'."));
        properties.put("title", Map.of(
                "type", "string",
                "description", "Optional human-readable title for the "
                        + "spawned process."));
        properties.put("steerContent", Map.of(
                "type", "string",
                "description", "Optional initial USER_CHAT_INPUT pushed "
                        + "into the spawned process's pending queue "
                        + "right after start (same semantics as "
                        + "process_create.steerContent)."));
        properties.put("params", Map.of(
                "type", "object",
                "description", "Engine-specific runtime parameters that "
                        + "override the matched recipe's defaults "
                        + "per-key. Same shape as process_create.params.",
                "additionalProperties", true));
        properties.put("fallbackOnNone", Map.of(
                "type", "boolean",
                "description", "When the selector returns NONE (no "
                        + "existing recipe matches), automatically spawn "
                        + "Slartibartfast to generate a fresh recipe and "
                        + "then spawn that recipe. Adds 60-180s to the "
                        + "tool round-trip when triggered. Default: true. "
                        + "Set false if the caller wants to handle NONE "
                        + "themselves (e.g. ask the user to clarify "
                        + "before generating a recipe)."));
        properties.put("asyncFallback", Map.of(
                "type", "boolean",
                "description", "Only meaningful when fallbackOnNone is "
                        + "true. When async=true: the tool spawns "
                        + "Slartibartfast and returns immediately with "
                        + "{outcome: PENDING, slartProcessId}; the caller "
                        + "is responsible for checking Slart's status "
                        + "later (typically via the parent-notification "
                        + "process-event that fires when Slart reaches "
                        + "CLOSED). The generated recipe is NOT auto-"
                        + "spawned in this mode — the caller must read "
                        + "Slart's persistedRecipePath and call "
                        + "process_create itself. Default: false (sync "
                        + "wait + auto-spawn)."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("name", "task"));
    }

    private final RecipeSelectorService selector;
    private final ThinkProcessService thinkProcessService;
    private final ProcessCreateTool processCreateTool;
    private final SlartibartfastFallback slartFallback;

    @Override
    public String name() {
        return "process_create_delegate";
    }

    @Override
    public String description() {
        return "Create a new think-process by describing the task in "
                + "natural language — the system picks the matching "
                + "recipe automatically and delegates to process_create. "
                + "Use this when you DON'T have a specific recipe name "
                + "in mind. By default, when no existing recipe fits "
                + "(NONE), Slartibartfast is auto-spawned to generate "
                + "a fresh recipe and that recipe is then spawned (adds "
                + "~60-180s). Pass `fallbackOnNone: false` to opt out "
                + "and let the caller handle NONE manually.";
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.sessionId() == null) {
            throw new ToolException(
                    "process_create_delegate requires a session scope");
        }
        String name = stringOrThrow(params, "name");
        String task = stringOrThrow(params, "task");
        String title = optString(params, "title");
        String steerContent = optString(params, "steerContent");
        boolean fallbackOnNone = optBoolean(params, "fallbackOnNone", true);
        boolean asyncFallback = optBoolean(params, "asyncFallback", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> callerParams = params.get("params") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;

        ThinkProcessDocument caller = resolveCaller(ctx);
        RecipeSelectorService.Result result = selector.select(caller, task);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decision", result.decision().name());
        out.put("recipe", result.recipeName());
        out.put("engine", result.engineName());
        out.put("rationale", result.rationale());

        if (result.decision() == RecipeSelectorService.Result.Decision.MATCH) {
            return spawnAndReturn(out, name, result.recipeName(), task,
                    title, steerContent, callerParams, ctx);
        }

        // NONE path. Either fall back to Slartibartfast for fresh
        // recipe generation, or surface the decision to the caller.
        if (!fallbackOnNone) {
            log.info("process_create_delegate name='{}' task='{}' → NONE (no fallback): {}",
                    name, abbrev(task, 80), result.rationale());
            return out;
        }

        log.info("process_create_delegate name='{}' → NONE, invoking Slart fallback "
                + "({} mode, rationale: {})",
                name, asyncFallback ? "async" : "sync", result.rationale());
        SlartibartfastFallback.Result fb = asyncFallback
                ? slartFallback.invokeAsync(caller, task, name)
                : slartFallback.invoke(caller, task, name);
        Map<String, Object> fallbackInfo = new LinkedHashMap<>();
        fallbackInfo.put("outcome", fb.outcome().name());
        fallbackInfo.put("slartProcessId", fb.slartProcessId());
        fallbackInfo.put("recipeName", fb.recipeName());
        fallbackInfo.put("recipePath", fb.recipePath());
        fallbackInfo.put("rationale", fb.rationale());
        out.put("fallback", fallbackInfo);

        // Async-mode never auto-spawns the generated recipe — that's
        // the whole point. Caller observes Slart's terminal event
        // (parent-notification on the calling process's pending queue)
        // and decides whether to spawn or report back to the user.
        // GENERATED is a sync-mode-only outcome.
        if (fb.outcome() != SlartibartfastFallback.Outcome.GENERATED) {
            log.info("process_create_delegate name='{}' fallback outcome={}: {}",
                    name, fb.outcome(), fb.rationale());
            return out;
        }

        // Slart produced a recipe — spawn it through the regular
        // create path. The recipe name carries the _slart/<runId>/
        // prefix that RecipeResolver knows how to resolve via the
        // project document cascade (Slart wrote it as a project doc
        // in PERSISTING).
        return spawnAndReturn(out, name, fb.recipeName(), task,
                title, steerContent, callerParams, ctx);
    }

    /**
     * Hands off to {@link ProcessCreateTool} with the matched (or
     * generated) recipe and threads the spawn-result back into the
     * delegate response. Same call shape regardless of how the
     * recipe was chosen — only the {@code decision} field upstream
     * tells you whether selector or fallback path picked it.
     */
    private Map<String, Object> spawnAndReturn(
            Map<String, Object> out,
            String name, String recipe, String task,
            @org.jspecify.annotations.Nullable String title,
            @org.jspecify.annotations.Nullable String steerContent,
            @org.jspecify.annotations.Nullable Map<String, Object> callerParams,
            ToolInvocationContext ctx) {
        Map<String, Object> createParams = new LinkedHashMap<>();
        createParams.put("name", name);
        createParams.put("recipe", recipe);
        if (title != null) createParams.put("title", title);
        // Pass the task as the spawned process's goal so engines
        // that surface a goal (Marvin, Vogon) start oriented.
        createParams.put("goal", task);
        if (steerContent != null) createParams.put("steerContent", steerContent);
        if (callerParams != null) createParams.put("params", callerParams);

        Map<String, Object> spawnResult = processCreateTool.invoke(createParams, ctx);
        out.put("process", spawnResult);
        log.info("process_create_delegate name='{}' → spawned recipe='{}'",
                name, recipe);
        return out;
    }

    // ──────────────────── helpers ────────────────────

    private ThinkProcessDocument resolveCaller(ToolInvocationContext ctx) {
        String pid = ctx.processId();
        if (pid == null || pid.isBlank()) {
            throw new ToolException(
                    "process_create_delegate must be invoked from a "
                            + "running process (no processId in context)");
        }
        return thinkProcessService.findById(pid)
                .orElseThrow(() -> new ToolException(
                        "calling process '" + pid + "' not found"));
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required");
        }
        return s.trim();
    }

    private static String optString(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
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
