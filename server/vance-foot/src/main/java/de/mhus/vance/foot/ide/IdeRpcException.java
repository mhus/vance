package de.mhus.vance.foot.ide;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Surfaces a JSON-RPC error response from the IDE plugin.
 * {@code code -32601 "Method not found"} is expected for {@code resources/list}
 * and {@code prompts/list} (planning §0); callers that probe must treat that
 * code as a non-error.
 */
public class IdeRpcException extends RuntimeException {

    private final int code;
    private final @Nullable JsonNode data;

    public IdeRpcException(int code, String message, @Nullable JsonNode data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public @Nullable JsonNode data() {
        return data;
    }
}
