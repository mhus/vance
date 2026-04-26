package de.mhus.vance.foot.tools;

import de.mhus.vance.api.tools.ToolSpec;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A capability local to the foot client that the brain can invoke
 * over WebSocket. Implementations are stateless Spring components
 * picked up by {@link ClientToolService}.
 *
 * <p>{@link #invoke} runs synchronously on the WebSocket-receive
 * thread; long-running work should hand off to a background executor
 * and return a job-id (see {@code ClientExecutorService} for the
 * pattern).
 */
public interface ClientTool {

    /** Stable unique name. Convention: {@code client_*} prefix. */
    String name();

    /** Short human-readable purpose, shown to the LLM. */
    String description();

    /**
     * {@code true} — advertised to the LLM on every turn.
     * {@code false} — only discoverable via {@code find_tools}.
     */
    boolean primary();

    /** JSON-Schema object describing invocation parameters. */
    Map<String, Object> paramsSchema();

    /** Executes the tool. The map shape is the LLM-facing JSON. */
    Map<String, Object> invoke(Map<String, Object> params);

    /** Default projection to the wire-format spec. */
    default ToolSpec toSpec() {
        return ToolSpec.builder()
                .name(name())
                .description(description())
                .primary(primary())
                .source("client")
                .paramsSchema(new LinkedHashMap<>(paramsSchema()))
                .build();
    }
}
