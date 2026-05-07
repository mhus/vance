package de.mhus.vance.toolpack.rest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Flat, JSON-Schema-friendly view of one OpenAPI operation. Built by
 * {@link OpenApiSpecLoader}; consumed by {@link RestEndpointTool} (and
 * future-extracted client-side variants).
 *
 * <p>{@code paramsSchema} is the merged JSON-Schema of all path-,
 * query- and (for body methods) requestBody parameters — exactly the
 * shape Vance's {@link de.mhus.vance.toolpack.Tool#paramsSchema()}
 * expects. Path-params are flagged in {@link #pathParamNames()} so the
 * URL builder can substitute them; query/header-params live in
 * {@link #queryParamNames()} and {@link #headerParamNames()}; the body
 * comes through under {@link #bodyParamName} (default {@code "body"}).
 */
public record OpenApiOperation(
        String operationId,
        String httpMethod,
        String pathTemplate,
        @Nullable String summary,
        @Nullable String description,
        Map<String, Object> paramsSchema,
        List<String> pathParamNames,
        List<String> queryParamNames,
        List<String> headerParamNames,
        @Nullable String bodyParamName,
        @Nullable String bodyContentType) {

    public OpenApiOperation {
        operationId = operationId == null ? "" : operationId;
        httpMethod = httpMethod == null ? "GET" : httpMethod.toUpperCase();
        pathTemplate = pathTemplate == null ? "/" : pathTemplate;
        paramsSchema = paramsSchema == null ? Map.of() : Map.copyOf(paramsSchema);
        pathParamNames = pathParamNames == null ? List.of() : List.copyOf(pathParamNames);
        queryParamNames = queryParamNames == null ? List.of() : List.copyOf(queryParamNames);
        headerParamNames = headerParamNames == null ? List.of() : List.copyOf(headerParamNames);
    }

    /**
     * Returns a fresh, mutable copy of {@link #paramsSchema} — useful
     * when the consumer wants to edit (e.g. add the body param under
     * a custom name).
     */
    public Map<String, Object> paramsSchemaMutable() {
        return new LinkedHashMap<>(paramsSchema);
    }
}
