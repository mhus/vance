package de.mhus.vance.brain.tools;

import de.mhus.vance.api.tools.ToolSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Runtime counterpart to {@link ToolSpec}: same metadata plus {@link
 * #invoke}.
 *
 * <p>Implementations are stateless — the dispatcher calls them
 * concurrently from different scopes. All per-call state lives in the
 * {@link ToolInvocationContext} argument.
 *
 * <p>Return values are plain maps rather than strongly typed results so
 * the dispatcher can serialise them straight into the LLM tool-result
 * channel without a per-tool adapter.
 */
public interface Tool {

    /** Stable unique name — the identifier the LLM uses to call it. */
    String name();

    /** Short human-readable purpose, shown to the LLM. */
    String description();

    /**
     * {@code true} — advertised to the LLM on every turn.
     * {@code false} — only discoverable via {@code find_tools}.
     */
    boolean primary();

    /**
     * JSON-Schema object describing invocation parameters. Keep the
     * schema small and object-shaped ({@code type: "object"}, {@code
     * properties}, {@code required}) — richer constructs are translated
     * best-effort by downstream adapters.
     */
    Map<String, Object> paramsSchema();

    /** Executes the tool. Throws {@link ToolException} on failure. */
    Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx);

    /**
     * Selector tags. Recipes can reference tools collectively via
     * {@code @<label>}. Built-in beans return an empty set by default;
     * configured tools override with the values from
     * {@code ServerToolDocument#labels}.
     */
    default Set<String> labels() {
        return Set.of();
    }

    /**
     * Default projection to the wire-format DTO. Overriding is rarely
     * useful — the wire contract matches this interface one-to-one.
     */
    default ToolSpec toSpec(String sourceId) {
        return ToolSpec.builder()
                .name(name())
                .description(description())
                .primary(primary())
                .source(sourceId)
                .paramsSchema(new LinkedHashMap<>(paramsSchema()))
                .build();
    }
}
