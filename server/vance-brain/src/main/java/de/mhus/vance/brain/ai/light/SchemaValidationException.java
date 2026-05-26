package de.mhus.vance.brain.ai.light;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * Schema-loop in {@link LightLlmService#callForJson} exhausted its
 * retry budget. Carries the last invalid value and the last
 * validation error joined, so the caller can surface a useful
 * diagnostic to its operator.
 */
@Getter
public class SchemaValidationException extends LightLlmException {

    private final int attempts;
    private final @Nullable Object lastInvalidValue;
    private final @Nullable String lastError;

    public SchemaValidationException(
            int attempts,
            @Nullable Object lastInvalidValue,
            @Nullable String lastError) {
        super("Schema not satisfied after " + attempts
                + " attempt(s); last error: "
                + (lastError == null ? "(none)" : lastError));
        this.attempts = attempts;
        this.lastInvalidValue = lastInvalidValue;
        this.lastError = lastError;
    }
}
