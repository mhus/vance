package de.mhus.vance.foot.script;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * One foot-side script run. {@code code} is the JavaScript source.
 * {@code sourceName} shows up in stack traces.
 */
public record ClientScriptRequest(
        String language,
        String code,
        @Nullable String sourceName,
        ClientExecutionContext executionContext,
        Duration timeout) {

    public ClientScriptRequest {
        if (!"js".equals(language)) {
            throw new IllegalArgumentException(
                    "Unsupported script language: " + language + " (only 'js' is allowed in v1)");
        }
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("code must not be empty");
        }
        if (executionContext == null) {
            throw new IllegalArgumentException("executionContext must not be null");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }
}
