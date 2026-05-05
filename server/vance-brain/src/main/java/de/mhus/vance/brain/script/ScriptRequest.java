package de.mhus.vance.brain.script;

import de.mhus.vance.brain.tools.ContextToolsApi;
import java.time.Duration;
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
 */
public record ScriptRequest(
        String language,
        String code,
        @Nullable String sourceName,
        ContextToolsApi tools,
        Duration timeout) {

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
    }
}
