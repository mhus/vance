package de.mhus.vance.brain.tools.js;

import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.ScriptResult;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Runs JavaScript snippets for accurate calculations (math, sorting,
 * filtering, aggregating, string processing, date math …) on the brain
 * via the sandboxed {@link ScriptExecutor}. The script sees the
 * {@code vance} host object and can call sibling tools through the same
 * allow-filter the LLM uses.
 */
@Component
@RequiredArgsConstructor
public class JavaScriptTool implements Tool {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "code", Map.of(
                            "type", "string",
                            "description",
                                    "JavaScript source. The value of the LAST "
                                            + "expression is returned."),
                    "timeoutMs", Map.of(
                            "type", "integer",
                            "description",
                                    "Wall-clock timeout in milliseconds (default 5000, max 30000).")),
            "required", List.of("code"));

    private final ScriptExecutor scriptExecutor;

    @Override
    public String name() {
        return "execute_javascript";
    }

    @Override
    public String description() {
        return "Execute JavaScript on the brain server for accurate calculations: "
                + "math, sorting, filtering, aggregating, string processing, date math etc. "
                + "The host binding 'vance' exposes vance.tools.call(name, params), "
                + "vance.context (read-only scope) and vance.log. "
                + "The return value of the last expression is returned.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        throw new ToolException(
                "execute_javascript requires the bound tools surface — "
                        + "call via the engine's ContextToolsApi");
    }

    @Override
    public Map<String, Object> invoke(
            Map<String, Object> params, ToolInvocationContext ctx, ContextToolsApi tools) {
        Object raw = params == null ? null : params.get("code");
        if (!(raw instanceof String code) || code.isBlank()) {
            throw new ToolException("'code' is required and must be a non-empty string");
        }
        Duration timeout = resolveTimeout(params);
        try {
            ScriptResult result = scriptExecutor.run(
                    new ScriptRequest("js", code, "execute_javascript", tools, timeout));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("value", result.value());
            out.put("durationMs", result.duration().toMillis());
            return out;
        } catch (ScriptExecutionException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", e.errorClass().name());
            out.put("message", e.getMessage());
            return out;
        }
    }

    private static Duration resolveTimeout(Map<String, Object> params) {
        if (params == null) {
            return DEFAULT_TIMEOUT;
        }
        Object raw = params.get("timeoutMs");
        if (raw instanceof Number n) {
            long ms = Math.max(1, Math.min(MAX_TIMEOUT.toMillis(), n.longValue()));
            return Duration.ofMillis(ms);
        }
        return DEFAULT_TIMEOUT;
    }
}
