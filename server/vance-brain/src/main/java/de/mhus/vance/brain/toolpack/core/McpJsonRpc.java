package de.mhus.vance.brain.toolpack.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * JSON-RPC 2.0 envelope helpers for MCP transports. Pure Java —
 * reuses the toolpack-internal JSON writer/reader from
 * {@link PackJson}.
 *
 * <p>Three message shapes:
 * <pre>
 *   request:      { jsonrpc:"2.0", id:N,   method:"...", params:{...} }
 *   response-ok:  { jsonrpc:"2.0", id:N,   result:{...} }
 *   response-err: { jsonrpc:"2.0", id:N,   error:{ code:N, message:"...", data:... } }
 *   notification: { jsonrpc:"2.0",         method:"...", params:{...} }   // no id
 * </pre>
 */
public final class McpJsonRpc {

    public static final String JSONRPC_VERSION = "2.0";

    private final AtomicLong nextId = new AtomicLong(1);

    /** Allocates a fresh request id. Strictly monotonic per instance. */
    public long allocId() {
        return nextId.getAndIncrement();
    }

    /** Builds a request frame as a JSON string. */
    public String buildRequest(long id, String method, @Nullable Map<String, Object> params) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", JSONRPC_VERSION);
        envelope.put("id", id);
        envelope.put("method", method);
        if (params != null) envelope.put("params", params);
        return PackJson.write(envelope);
    }

    /** Builds a notification frame (no id, no response expected). */
    public String buildNotification(String method, @Nullable Map<String, Object> params) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", JSONRPC_VERSION);
        envelope.put("method", method);
        if (params != null) envelope.put("params", params);
        return PackJson.write(envelope);
    }

    /**
     * Parses a single JSON-RPC frame. Returns either a {@link Frame.Request}
     * (id present + method present), {@link Frame.Response} (id present, no
     * method), or {@link Frame.Notification} (method present, no id).
     */
    @SuppressWarnings("unchecked")
    public static Frame parse(String json) {
        Object parsed = PackJson.read(json);
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("JSON-RPC: top-level must be an object: " + json);
        }
        Map<String, Object> frame = (Map<String, Object>) raw;
        Object idRaw = frame.get("id");
        String method = frame.get("method") instanceof String s ? s : null;
        Long id = idRaw instanceof Number n ? n.longValue() : null;

        if (method != null && id != null) {
            return new Frame.Request(id, method, asMap(frame.get("params")));
        }
        if (method != null) {
            return new Frame.Notification(method, asMap(frame.get("params")));
        }
        if (id == null) {
            throw new IllegalArgumentException("JSON-RPC: frame has neither method nor id: " + json);
        }
        Object error = frame.get("error");
        if (error != null) {
            return new Frame.Response(id, null, asMap(error));
        }
        return new Frame.Response(id, frame.get("result"), null);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> asMap(@Nullable Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** Sealed-ish frame hierarchy — three subtypes by JSON-RPC role. */
    public sealed interface Frame {
        record Request(long id, String method, @Nullable Map<String, Object> params) implements Frame { }
        record Notification(String method, @Nullable Map<String, Object> params) implements Frame { }
        record Response(long id, @Nullable Object result, @Nullable Map<String, Object> error) implements Frame { }
    }

    /**
     * Surfaces a JSON-RPC error response as a thrown exception. Used by
     * the MCP transports to fail the awaiting request future when the
     * server returns an error frame.
     */
    public static final class JsonRpcException extends RuntimeException {
        private final long code;
        private final @Nullable Object data;

        public JsonRpcException(long code, String message, @Nullable Object data) {
            super("JSON-RPC error " + code + ": " + message);
            this.code = code;
            this.data = data;
        }

        public long code() { return code; }
        public @Nullable Object data() { return data; }

        public static JsonRpcException fromMap(Map<String, Object> error) {
            long code = error.get("code") instanceof Number n ? n.longValue() : -32000;
            String msg = error.get("message") instanceof String s ? s : "(no message)";
            return new JsonRpcException(code, msg, error.get("data"));
        }
    }
}
