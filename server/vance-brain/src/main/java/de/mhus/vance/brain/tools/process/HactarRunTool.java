package de.mhus.vance.brain.tools.process;

import de.mhus.vance.brain.hactar.HactarEngine;
import de.mhus.vance.toolpack.SpawnTool;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Convenience-wrapper around {@code process_create(recipe="hactar-run",
 * params={…})}. Saves callers (LLM in Arthur / Eddie / Ford, JavaScript
 * orchestrators via {@code vance.tools.call}, scheduler templates) from
 * knowing the bundled recipe name and the engine-param shape.
 *
 * <p>Equivalent to:
 * <pre>
 *   process_create({
 *     name: "hactar-<uuid>",
 *     goal: "Run script <path>",
 *     recipe: "hactar-run",
 *     params: {
 *       scriptRef: "<path>",
 *       language: "js",
 *       validateBeforeRun: false,
 *       scriptAllowedTools: [...],
 *       scriptParams: { ... },
 *       timeout: 300
 *     }
 *   })
 * </pre>
 *
 * <p>{@code @SpawnTool}-annotated: the trigger-scoped sandbox refuses
 * to dispatch this from inside a script, same as
 * {@link ProcessCreateTool} and {@code script_run_doc}. A script that
 * can spawn another script-running process at will would defeat the
 * isolation we get from trigger-scoped sandboxes.
 *
 * <p>See {@code planning/script-architect-executor-split.md} §5.4.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@SpawnTool
public class HactarRunTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("scriptRef", Map.of(
                "type", "string",
                "description", "Document path to the script — REQUIRED. "
                        + "Resolved via the standard cascade "
                        + "(project → _vance → resource)."));
        properties.put("name", Map.of(
                "type", "string",
                "description", "Stable process name. Optional — when "
                        + "omitted, the tool derives a stable name from "
                        + "scriptRef + a short random suffix."));
        properties.put("validateBeforeRun", Map.of(
                "type", "boolean",
                "description", "Run an LLM deep-validate pass before "
                        + "EXECUTING. Default false. Useful when the "
                        + "script came from an untrusted source or was "
                        + "hand-edited without prior Slart-validation."));
        properties.put("scriptAllowedTools", Map.of(
                "type", "array",
                "description", "Tools the script may call via "
                        + "`vance.tools.call(...)`. When unset, the "
                        + "script gets no tool surface and can only "
                        + "exercise pure-JS logic.",
                "items", Map.of("type", "string")));
        properties.put("scriptParams", Map.of(
                "type", "object",
                "description", "Bindings passed to the script as "
                        + "`vance.params.*`.",
                "additionalProperties", true));
        properties.put("timeout", Map.of(
                "type", "integer",
                "description", "Wall-clock timeout in seconds. "
                        + "Default 300 (5 minutes). Header-declared "
                        + "@timeout still clamps via "
                        + "vance.script.timeout.max."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("scriptRef"));
    }

    /** Lazy lookup — see {@link ProcessCreateTool#thinkEngineServiceProvider}
     *  for the rationale. The bean graph cycles otherwise. */
    private final ObjectProvider<ProcessCreateTool> processCreateProvider;

    @Override
    public String name() {
        return "hactar_run";
    }

    @Override
    public String description() {
        return "Run a JavaScript orchestrator script via Hactar. "
                + "Spawns a new Hactar process bound to the bundled "
                + "`hactar-run` recipe; the script body is loaded "
                + "from the supplied document path, validated, and "
                + "executed in a sandboxed GraalJS context. "
                + "Returns the spawned process info — poll its "
                + "ProcessEvent for the return value.";
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
    public Map<String, Object> invoke(
            Map<String, Object> params, ToolInvocationContext ctx) {
        String scriptRef = ScriptRunDocToolStrings.stringParam(params, "scriptRef", true);
        String name = ScriptRunDocToolStrings.stringParam(params, "name", false);
        if (name == null || name.isBlank()) {
            name = deriveProcessName(scriptRef);
        }

        Map<String, Object> hactarParams = new LinkedHashMap<>();
        hactarParams.put(HactarEngine.SCRIPT_REF_KEY, scriptRef);
        Object validateBeforeRun = params == null ? null : params.get("validateBeforeRun");
        if (validateBeforeRun instanceof Boolean b) {
            hactarParams.put(HactarEngine.VALIDATE_BEFORE_RUN_KEY, b);
        }
        Object allowedTools = params == null ? null : params.get("scriptAllowedTools");
        if (allowedTools instanceof List<?> list) {
            hactarParams.put(HactarEngine.SCRIPT_ALLOWED_TOOLS_KEY, list);
        }
        Object scriptParams = params == null ? null : params.get("scriptParams");
        if (scriptParams instanceof Map<?, ?> m) {
            hactarParams.put(HactarEngine.SCRIPT_PARAMS_KEY, m);
        }
        Object timeout = params == null ? null : params.get("timeout");
        if (timeout instanceof Number n) {
            hactarParams.put(HactarEngine.TIMEOUT_KEY, n.intValue());
        }

        Map<String, Object> processCreateParams = new LinkedHashMap<>();
        processCreateParams.put("name", name);
        processCreateParams.put("goal", "Run script " + scriptRef);
        processCreateParams.put("recipe", "hactar-run");
        processCreateParams.put("params", hactarParams);

        ProcessCreateTool inner = processCreateProvider.getIfAvailable();
        if (inner == null) {
            throw new ToolException(
                    "hactar_run: ProcessCreateTool bean unavailable");
        }
        return inner.invoke(processCreateParams, ctx);
    }

    /**
     * Stable process name from the script ref's basename + 8-hex
     * random suffix. Stays under the session-name uniqueness
     * constraint and stays readable in process listings.
     */
    private static String deriveProcessName(String scriptRef) {
        String base = scriptRef;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        if (base.isBlank()) base = "hactar";
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        return "hactar-" + base + "-" + suffix;
    }

    /** Tiny alias to reuse the string-param helper from ScriptRunDocTool
     *  without making it public — the brain has dozens of these helpers
     *  scattered around already, no point duplicating here. */
    private static final class ScriptRunDocToolStrings {
        static String stringParam(Map<String, Object> p, String key, boolean required) {
            Object raw = p == null ? null : p.get(key);
            if (raw == null || (raw instanceof String s && s.isBlank())) {
                if (required) {
                    throw new ToolException("missing required parameter '" + key + "'");
                }
                return null;
            }
            if (raw instanceof String s) return s;
            throw new ToolException("parameter '" + key + "' must be a string");
        }
    }
}
