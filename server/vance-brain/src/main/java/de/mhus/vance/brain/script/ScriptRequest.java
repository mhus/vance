package de.mhus.vance.brain.script;

import de.mhus.vance.brain.tools.ContextToolsApi;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One script run. The {@link ContextToolsApi} carries the
 * scope-bound tool surface — the same one the engine's LLM tool
 * loop uses, so a script call goes through the same allow-filter,
 * permission and listener path.
 *
 * <p>{@code code} is the JavaScript source. {@code sourceName} shows
 * up in stack traces — pass the originating path or a stable label
 * to make script errors readable.
 *
 * <p>{@code bindings} are injected into the script's global scope
 * before evaluation: each entry becomes a top-level variable visible
 * to the script. Used by {@code scripted}-type server tools to pass
 * typed inputs (declared in the tool's parameters schema) through
 * verbatim — e.g. binding {@code {"a": 5, "b": 3}} makes the script
 * able to write {@code a + b}.
 */
public record ScriptRequest(
        String language,
        String code,
        @Nullable String sourceName,
        ContextToolsApi tools,
        Duration timeout,
        Map<String, @Nullable Object> bindings) {

    public ScriptRequest {
        if (!"js".equals(language)) {
            throw new IllegalArgumentException(
                    "Unsupported script language: " + language + " (only 'js' is allowed in v1)");
        }
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("code must not be empty");
        }
        if (tools == null) {
            throw new IllegalArgumentException("tools must not be null");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (bindings == null) {
            throw new IllegalArgumentException("bindings must not be null (use Map.of() instead)");
        }
        if (bindings.containsKey("vance")) {
            throw new IllegalArgumentException(
                    "binding name 'vance' is reserved for the host API");
        }
    }

    /**
     * Convenience constructor for the historical 5-argument shape — no
     * bindings beyond the {@code vance} host object. Existing call
     * sites continue to compile unchanged.
     */
    public ScriptRequest(
            String language,
            String code,
            @Nullable String sourceName,
            ContextToolsApi tools,
            Duration timeout) {
        this(language, code, sourceName, tools, timeout, Map.of());
    }
}
