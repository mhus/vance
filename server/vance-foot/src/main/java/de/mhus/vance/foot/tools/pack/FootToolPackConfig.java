package de.mhus.vance.foot.tools.pack;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Mirror of the {@code ServerToolDocument} essentials, in JSON form.
 * One file per pack under {@code ~/.vance/foot-tools/}. Loaded by
 * {@link FootToolPackLoader} via Jackson.
 *
 * <p>Schema:
 * <pre>
 *   {
 *     "name":  "jira",                  // pack-namespace; sub-tools become <name>__<sub>
 *     "type":  "rest_api",              // toolpack factory type
 *     "description": "JIRA REST API",
 *     "primary": false,
 *     "defaultDeferred": true,
 *     "enabled": true,                  // optional; default true
 *     "labels": ["external", "jira"],
 *     "disabledSubTools": ["adminOps"], // optional; per-pack sub-tool denylist
 *     "parameters": { ... }             // type-specific block (RestApiConfig / McpConfig)
 *   }
 * </pre>
 *
 * <p>Disable mechanics — both work:
 * <ol>
 *   <li>{@code "enabled": false} in the JSON content</li>
 *   <li>Filename suffix {@code .json.disabled} (loader skips on path-match)</li>
 * </ol>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FootToolPackConfig(
        String name,
        String type,
        @JsonProperty("description") @Nullable String description,
        @JsonProperty("primary") @Nullable Boolean primaryBox,
        @JsonProperty("defaultDeferred") @Nullable Boolean defaultDeferredBox,
        @Nullable Boolean enabled,
        @Nullable List<String> labels,
        @Nullable List<String> disabledSubTools,
        @Nullable Map<String, Object> parameters) {

    public FootToolPackConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("foot tool pack: 'name' is required");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                    "foot tool pack '" + name + "': 'type' is required");
        }
        labels = labels == null ? List.of() : List.copyOf(labels);
        disabledSubTools = disabledSubTools == null ? List.of() : List.copyOf(disabledSubTools);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    /** {@code true} when omitted in JSON or set to {@code true}. */
    public boolean primary() {
        return primaryBox != null && primaryBox;
    }

    /** {@code true} when explicitly set to {@code true}. */
    public boolean defaultDeferred() {
        return defaultDeferredBox != null && defaultDeferredBox;
    }

    /** {@code true} when neither {@code enabled:false} nor disabled-by-filename. */
    public boolean isEffectivelyEnabled() {
        return enabled == null || enabled;
    }

    public Set<String> labelsAsSet() {
        return labels == null ? Set.of() : new LinkedHashSet<>(labels);
    }

    public Set<String> disabledSubToolsAsSet() {
        return disabledSubTools == null ? Set.of() : new LinkedHashSet<>(disabledSubTools);
    }

    public Map<String, Object> parametersOrEmpty() {
        return parameters == null ? new LinkedHashMap<>() : parameters;
    }
}
