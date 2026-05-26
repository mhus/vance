package de.mhus.vance.brain.ai.light;

import java.util.Map;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input to a {@link LightLlmService} call. Built via
 * {@link #builder()}. Pebble vars, schema and maxAttempts are
 * optional — the request still needs a recipe, a user prompt, and
 * a tenant scope at minimum.
 */
@Value
@Builder
public class LightLlmRequest {

    /**
     * Name of the recipe used as config profile. Must be loadable from
     * the recipe cascade and marked {@code internal: true} — otherwise
     * the service rejects the request to prevent accidental reuse of a
     * spawnable recipe.
     */
    String recipeName;

    /**
     * The user-message content. Appended after the recipe's
     * Pebble-rendered {@code promptPrefix} (the system message).
     */
    String userPrompt;

    /**
     * Additional Pebble template variables made available when
     * rendering {@code promptPrefix}. Merged on top of the standard
     * render context — caller-supplied keys win on conflict.
     * {@code null} means "no extra vars".
     */
    @Nullable Map<String, Object> pebbleVars;

    /**
     * JSON-Schema-light map describing the expected reply shape.
     * Only consulted by {@link LightLlmService#callForJson}; ignored
     * by {@link LightLlmService#call}. {@code null} means "no schema
     * validation" (callForJson then simply parses to a Map).
     */
    @Nullable Map<String, Object> schema;

    /**
     * Override the recipe's {@code params.maxAttempts} or the global
     * default. {@code null} means "use recipe / global default".
     */
    @Nullable Integer maxAttempts;

    /** Required. Tenant scope for setting cascades + API-key lookup. */
    String tenantId;

    /** Optional. Project scope for setting cascades. */
    @Nullable String projectId;

    /**
     * Optional. Innermost setting-cascade scope. Light-LLM calls
     * typically run from a tool invoked by a process, so passing the
     * caller's processId lets per-process setting overrides apply.
     */
    @Nullable String processId;
}
