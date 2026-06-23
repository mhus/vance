package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianControlEngine;
import de.mhus.vance.brain.trillian.TrillianInternalApi;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Sets one attribute on the paired Trillian-User worker. Attributes
 * are a free-form {@code Map<String, Object>} that the active
 * Trillian Nature consumes — Nature-0 renders them as a key/value
 * block in the user-loop's system prompt; later Natures may use
 * them for behavioural switches (mode hints, token budget,
 * persona traits, …).
 *
 * <p>Convention is caller-defined — Control's LLM picks the
 * attribute names. Nature documentation (when introduced)
 * recommends well-known names per Nature.
 */
@Component
@RequiredArgsConstructor
public class UserAttrSetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Attribute key. Free-form; the active "
                                    + "Trillian Nature decides what to do with it."),
                    "value", Map.of(
                            "type", "string",
                            "description", "Attribute value. Free-form text; can be a "
                                    + "phrase, a number, a sentence — the active Nature "
                                    + "decides how to read it.")),
            "required", List.of("name", "value"));

    private final TrillianInternalApi api;

    @Override
    public String name() {
        return "user_attr_set";
    }

    @Override
    public String description() {
        return "Set a free-form attribute on the paired Trillian-User "
                + "worker. Attributes shape how the worker behaves "
                + "(persona, mode hints, preferences). The current "
                + "Nature consumes them — Nature-0 simply surfaces them "
                + "in the worker's prompt.";
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
    public Set<String> labels() {
        return Set.of("executive");
    }

    @Override
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianControlEngine.ROLE_TRILLIAN_CONTROL);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("user_attr_set requires a process scope");
        }
        Object rawName = params == null ? null : params.get("name");
        Object rawValue = params == null ? null : params.get("value");
        if (!(rawName instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required and must be non-empty");
        }
        if (rawValue == null) {
            throw new ToolException("'value' is required");
        }

        boolean ok = api.setPeerAttribute(ctx.processId(), name.trim(), rawValue);
        if (!ok) {
            throw new ToolException(
                    "No Trillian-User peer found — this tool is only available "
                            + "inside a Trillian-Control session");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name.trim());
        out.put("value", rawValue);
        out.put("status", "set");
        return out;
    }
}
