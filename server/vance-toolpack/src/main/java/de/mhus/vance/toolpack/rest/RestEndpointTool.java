package de.mhus.vance.toolpack.rest;

import de.mhus.vance.toolpack.rest.OpenApiOperation;
import de.mhus.vance.toolpack.rest.RestHttpInvoker;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Set;

/**
 * Single REST endpoint exposed as a Vance {@link Tool}. Built by
 * {@link RestApiToolPackFactory}; one instance per OpenAPI operation
 * in the pack. Tool name is {@code <packName>__<operationId>} per the
 * pack-naming convention (see {@code planning/server-tool-providers.md}).
 *
 * <p>Schema, labels and the deferred flag come baked in from the
 * factory at materialisation time. The {@code description} is the
 * operation's {@code summary} (or {@code description}, or a synthesised
 * fallback) — what the LLM reads when deciding whether to call.
 */
public final class RestEndpointTool implements Tool {

    private final String fullName;
    private final String description;
    private final Set<String> labels;
    private final boolean deferred;
    private final boolean primary;
    private final String searchHint;
    private final OpenApiOperation operation;
    private final RestHttpInvoker invoker;

    public RestEndpointTool(
            String fullName,
            String description,
            Set<String> labels,
            boolean deferred,
            boolean primary,
            String searchHint,
            OpenApiOperation operation,
            RestHttpInvoker invoker) {
        this.fullName = fullName;
        this.description = description;
        this.labels = labels == null ? Set.of() : labels;
        this.deferred = deferred;
        this.primary = primary;
        this.searchHint = searchHint == null ? "" : searchHint;
        this.operation = operation;
        this.invoker = invoker;
    }

    @Override public String name() { return fullName; }
    @Override public String description() { return description; }
    @Override public boolean primary() { return primary; }
    @Override public boolean deferred() { return deferred; }
    @Override public String searchHint() { return searchHint; }
    @Override public Set<String> labels() { return labels; }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "401/403 = credential expired or scope missing; 404 = resource path; 429 = rate-limited, back off; 5xx = retry; timeout = remote slow.";
    }

    @Override
    public Set<String> prakLabels() {
        return Set.of("integration", "rest");
    }

    @Override public Map<String, Object> paramsSchema() { return operation.paramsSchema(); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        return invoker.execute(operation, params == null ? Map.of() : params, ctx);
    }
}
