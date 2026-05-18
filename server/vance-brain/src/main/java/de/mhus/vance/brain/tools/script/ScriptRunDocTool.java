package de.mhus.vance.brain.tools.script;

import de.mhus.vance.api.action.ScriptSource;
import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.brain.action.ActionInvocation;
import de.mhus.vance.brain.action.ActionResult;
import de.mhus.vance.brain.action.ScriptActionExecutor;
import de.mhus.vance.brain.action.TriggerContext;
import de.mhus.vance.brain.action.TriggerKind;
import de.mhus.vance.toolpack.SpawnTool;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Runs a JS script stored in the document cascade
 * ({@code project → _vance → resource}). Thin wrapper around
 * {@link ScriptActionExecutor} that exposes one parameter shape
 * to the LLM and translates the result back into a tool-result map.
 *
 * <p>See {@code planning/trigger-actions.md} §4.5. The companion
 * {@code script_run_workspace} tool covers workspace-resident scripts.
 *
 * <p>{@code @SpawnTool}-annotated so the trigger-scoped sandbox refuses
 * to dispatch it from a trigger-scoped script — a script that can run
 * other scripts at will would be the same escalation we're trying to
 * prevent for {@code process_create}.
 */
@Component
@RequiredArgsConstructor
@SpawnTool
public class ScriptRunDocTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
                "type", "string",
                "description", "Document path of the .js script, "
                        + "resolved via the standard cascade "
                        + "(project → _vance → resource)."));
        properties.put("params", Map.of(
                "type", "object",
                "description", "Arbitrary key/value map passed to the "
                        + "script as the top-level 'args' binding.",
                "additionalProperties", true));
        properties.put("timeoutSeconds", Map.of(
                "type", "integer",
                "description", "Wall-clock timeout for the script run. "
                        + "Default 30s."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("path"));
    }

    private final ScriptActionExecutor scriptActionExecutor;

    @Override
    public String name() {
        return "script_run_doc";
    }

    @Override
    public String description() {
        return "Run a JavaScript file stored in the document cascade. "
                + "Same sandbox as skill scripts; returns the mapped "
                + "value or an error object.";
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
        String path = stringParam(params, "path", true);
        Integer timeoutSeconds = intParam(params, "timeoutSeconds");
        Map<String, Object> userParams = mapParam(params, "params");

        TriggerAction.Script action = new TriggerAction.Script(
                ScriptSource.DOCUMENT, /*dirName*/ null, path,
                timeoutSeconds, userParams, ctx.userId());
        TriggerContext triggerCtx = new TriggerContext(
                ctx.tenantId(),
                StringUtils.defaultString(ctx.projectId(), ""),
                ctx.userId(),
                /*correlationId*/ null,
                "tool:" + name(),
                ctx.sessionId(),
                ctx.processId());
        ActionResult result = scriptActionExecutor.execute(new ActionInvocation<>(
                action, triggerCtx, TriggerKind.TOOL));
        return toResultMap(result);
    }

    static String stringParam(Map<String, Object> params, String key, boolean required) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            if (required) {
                throw new ToolException("missing required parameter '" + key + "'");
            }
            return null;
        }
        if (raw instanceof String s) return s;
        throw new ToolException("parameter '" + key + "' must be a string");
    }

    static Integer intParam(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return null;
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new ToolException("parameter '" + key + "' must be an integer");
            }
        }
        throw new ToolException("parameter '" + key + "' must be an integer");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapParam(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            throw new ToolException("parameter '" + key + "' must be a map");
        }
        return (Map<String, Object>) m;
    }

    static Map<String, Object> toResultMap(ActionResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcome", result.outcome().name().toLowerCase(java.util.Locale.ROOT));
        if (result.output() != null) {
            out.put("output", result.output());
        }
        if (result.errorMessage() != null) {
            out.put("error", result.errorMessage());
        }
        return out;
    }
}
