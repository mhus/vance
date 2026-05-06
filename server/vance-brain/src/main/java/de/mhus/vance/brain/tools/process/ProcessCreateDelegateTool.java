package de.mhus.vance.brain.tools.process;

import de.mhus.vance.brain.delegate.RecipeSelectorService;
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
        SCHEMA = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("name", "task"));
    }

    private final RecipeSelectorService selector;
    private final ThinkProcessService thinkProcessService;
    private final ProcessCreateTool processCreateTool;

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
                + "in mind. Returns the selector's decision plus the "
                + "spawned process info on MATCH, or a NONE result "
                + "(no spawn) when nothing in the project fits — let "
                + "the caller decide whether to fall back to "
                + "Slartibartfast for a freshly-generated recipe or "
                + "ask the user to clarify.";
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

        if (result.decision() == RecipeSelectorService.Result.Decision.NONE) {
            log.info("process_create_delegate name='{}' task='{}' → NONE: {}",
                    name, abbrev(task, 80), result.rationale());
            return out;
        }

        // MATCH — hand off to the regular create path so cascade
        // resolution + profile inheritance + engine start all happen
        // exactly as for an explicit process_create call.
        Map<String, Object> createParams = new LinkedHashMap<>();
        createParams.put("name", name);
        createParams.put("recipe", result.recipeName());
        if (title != null) createParams.put("title", title);
        // The selector already used the task as semantic input; pass
        // it through as the spawned process's `goal` so engines that
        // surface a goal (Marvin, Vogon) start oriented.
        createParams.put("goal", task);
        if (steerContent != null) createParams.put("steerContent", steerContent);
        if (callerParams != null) createParams.put("params", callerParams);

        Map<String, Object> spawnResult = processCreateTool.invoke(createParams, ctx);
        out.put("process", spawnResult);
        log.info("process_create_delegate name='{}' → MATCH recipe='{}' engine='{}'",
                name, result.recipeName(), result.engineName());
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

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
