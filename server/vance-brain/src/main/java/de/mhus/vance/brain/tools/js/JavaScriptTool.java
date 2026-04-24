package de.mhus.vance.brain.tools.js;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Runs JavaScript snippets for accurate calculations (math, sorting,
 * filtering, aggregating, string processing, date math …). Ported from
 * the ai-assistant prototype; execution delegates to {@link JsEngine},
 * which prefers GraalJS and falls back to Rhino.
 *
 * <p>Primary: the LLM should reach for this instead of guessing at
 * arithmetic. Output is the string form of the last expression's
 * value — errors come back as {@code "ERROR: …"} strings so the model
 * can read and react rather than having the turn crash.
 */
@Component
@RequiredArgsConstructor
public class JavaScriptTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "code", Map.of(
                            "type", "string",
                            "description",
                                    "JavaScript source. The value of the LAST "
                                            + "expression is returned as a string.")),
            "required", List.of("code"));

    private final JsEngine jsEngine;

    @Override
    public String name() {
        return "execute_javascript";
    }

    @Override
    public String description() {
        return "Execute JavaScript for accurate calculations: math, sorting, "
                + "filtering, aggregating, string processing, date math etc. "
                + "Prefer this over guessing numerical or algorithmic answers. "
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
        Object raw = params == null ? null : params.get("code");
        if (!(raw instanceof String code) || code.isBlank()) {
            throw new ToolException("'code' is required and must be a non-empty string");
        }
        return Map.of(
                "result", jsEngine.eval(code),
                "engine", jsEngine.mode().name().toLowerCase());
    }
}
