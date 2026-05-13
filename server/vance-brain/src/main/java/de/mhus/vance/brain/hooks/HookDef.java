package de.mhus.vance.brain.hooks;

import de.mhus.vance.api.hooks.HookEventName;
import de.mhus.vance.api.hooks.HookSource;
import de.mhus.vance.api.hooks.HookType;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Parsed hook YAML document. Immutable record produced by
 * {@link HookYamlParser}; consumed by the runners and the registry.
 *
 * <p>For {@code type == JS} only {@link #script} is populated; for
 * {@code type == LLM} only {@link #prompt} / {@link #model} /
 * {@link #maxTokens}. The parser validates that the right fields are
 * present.
 */
public record HookDef(
        String name,
        HookEventName event,
        HookSource source,
        HookType type,
        boolean enabled,
        @Nullable String description,
        Duration timeout,
        @Nullable List<String> tags,
        String yamlBody,
        @Nullable String createdByUserId,

        // JS-only
        @Nullable String script,

        // LLM-only
        @Nullable String model,
        @Nullable Integer maxTokens,
        @Nullable String prompt) {

    public String sourceKey() {
        return HookSourceKeys.sourceFor(event.wireName(), name);
    }
}
