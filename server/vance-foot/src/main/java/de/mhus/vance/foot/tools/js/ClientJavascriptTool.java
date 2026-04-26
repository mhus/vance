package de.mhus.vance.foot.tools.js;

import de.mhus.vance.foot.tools.ClientTool;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code client_javascript} — runs a JS snippet on the foot host.
 * Counterpart to the brain-side {@code execute_javascript}; use this
 * when the LLM wants quick computation in the user's environment
 * (with access to whatever the user's machine has at hand).
 */
@Component
@RequiredArgsConstructor
public class ClientJavascriptTool implements ClientTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "code", Map.of(
                            "type", "string",
                            "description",
                                    "JavaScript source. The value of the LAST "
                                            + "expression is returned as a string.")),
            "required", List.of("code"));

    private final ClientJsEngine jsEngine;

    @Override
    public String name() {
        return "client_javascript";
    }

    @Override
    public String description() {
        return "Execute JavaScript on the user's machine (foot client). "
                + "The value of the last expression is returned as a string. "
                + "Use this for fast local computation.";
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
    public Map<String, Object> invoke(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("code");
        if (!(raw instanceof String code) || code.isBlank()) {
            throw new IllegalArgumentException(
                    "'code' is required and must be a non-empty string");
        }
        return Map.of(
                "result", jsEngine.eval(code),
                "engine", jsEngine.mode().name().toLowerCase());
    }
}
