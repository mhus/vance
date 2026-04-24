package de.mhus.vance.api.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Wire-format description of a tool.
 *
 * <p>Used in both directions: the server advertises its tools to clients
 * (and meta-tools to the LLM), and clients push their own tools to the
 * server on connect. Deliberately flat JSON with no behaviour — the
 * runtime counterpart lives in {@code de.mhus.vance.brain.tools.Tool}.
 *
 * <p>{@link #paramsSchema} is a JSON-Schema subset (object schemas with
 * named properties and a {@code required} list) — enough to translate to
 * langchain4j's parameter format without pulling the full JSON-Schema
 * dependency into the API contract.
 *
 * <p>{@link #primary} controls visibility to the LLM. Primary tools are
 * listed up-front in every turn; secondary tools are only shown when the
 * LLM asks via the {@code find_tools} meta-tool.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("tools")
public class ToolSpec {

    /** Stable unique name — the identifier the LLM uses to call the tool. */
    private String name;

    /** Short human-readable purpose, shown to the LLM. */
    private String description;

    /**
     * {@code true} — tool is advertised to the LLM on every turn.
     * {@code false} — tool is only discoverable via {@code find_tools}.
     */
    private boolean primary;

    /**
     * Origin of the tool — {@code "server"}, {@code "client"}, or a plugin
     * identifier. Informational; dispatching goes through the tool's
     * {@code ToolSource}, not this field.
     */
    private @Nullable String source;

    /** JSON-Schema object describing the tool's invocation parameters. */
    @Builder.Default
    private Map<String, Object> paramsSchema = new LinkedHashMap<>();
}
