package de.mhus.vance.toolpack.mcp;

import de.mhus.vance.api.toolhealth.HttpStatusError;
import de.mhus.vance.api.toolhealth.RetryAfterError;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * An MCP server answered over HTTP with a non-2xx status.
 *
 * <p>Still an {@link IllegalStateException}, which is what this failure always was,
 * so nothing that caught that changes. What it adds is the status and the parsed
 * {@code Retry-After}, so a caller can tell a rate limit from an expired token from
 * a server that is down without reading the message.
 */
public class McpHttpStatusException extends IllegalStateException implements HttpStatusError, RetryAfterError {

    private final int status;
    private final @Nullable Instant retryAfter;

    public McpHttpStatusException(String message, int status, @Nullable Instant retryAfter) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    @Override
    public int httpStatus() {
        return status;
    }

    @Override
    public @Nullable Instant retryAfter() {
        return retryAfter;
    }
}
