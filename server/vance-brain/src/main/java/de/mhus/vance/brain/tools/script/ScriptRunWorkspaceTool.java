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
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Runs a JS script stored inside a workspace RootDir. Companion to
 * {@link ScriptRunDocTool} — same outcome shape, different source path.
 *
 * <p>The RootDir must already exist: workspace-source scripts are
 * caller-managed, the executor never auto-creates a RootDir for them
 * (see {@code specification/trigger-actions.md} §6.1).
 */
@Component
@RequiredArgsConstructor
@SpawnTool
public class ScriptRunWorkspaceTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("dirName", Map.of(
                "type", "string",
                "description", "Existing workspace RootDir name to load "
                        + "the script from."));
        properties.put("path", Map.of(
                "type", "string",
                "description", "Path of the .js file inside the RootDir."));
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
                "required", List.of("dirName", "path"));
    }

    private final ScriptActionExecutor scriptActionExecutor;

    @Override
    public String name() {
        return "script_run_workspace";
    }

    @Override
    public String description() {
        return "Run a JavaScript file stored in a workspace RootDir. "
                + "The RootDir must exist; the executor does not "
                + "auto-create it. Returns the mapped value or error.";
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
        String dirName = ScriptRunDocTool.stringParam(params, "dirName", true);
        String path = ScriptRunDocTool.stringParam(params, "path", true);
        Integer timeoutSeconds = ScriptRunDocTool.intParam(params, "timeoutSeconds");
        Map<String, Object> userParams = ScriptRunDocTool.mapParam(params, "params");

        TriggerAction.Script action = new TriggerAction.Script(
                ScriptSource.WORKSPACE, dirName, path, timeoutSeconds, userParams, ctx.userId());
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
        return ScriptRunDocTool.toResultMap(result);
    }
}
