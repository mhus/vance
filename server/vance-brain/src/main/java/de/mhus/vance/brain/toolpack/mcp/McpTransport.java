package de.mhus.vance.brain.toolpack.mcp;

import de.mhus.vance.brain.toolpack.core.McpJsonRpc;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Abstract transport for an MCP connection. Implementations:
 *
 * <ul>
 *   <li>{@link McpStdioTransport} — subprocess + stdin/stdout JSON-RPC.</li>
 *   <li>{@link McpHttpTransport} — Streamable HTTP (single endpoint with
 *       optional SSE upgrade) or legacy HTTP+SSE (split endpoints).</li>
 * </ul>
 *
 * <p>Lifecycle: {@link #open} opens the underlying connection (spawn
 * subprocess, set up SSE channel, …). {@link #close} releases it.
 * {@link #sendRequest} sends a JSON-RPC request and blocks for the
 * matching response. {@link #setNotificationHandler} routes
 * server-pushed notifications (e.g. {@code notifications/tools/list_changed})
 * to the connection layer.
 *
 * <p>Implementations are stateful but thread-safe — {@link #sendRequest}
 * may be called concurrently.
 */
public interface McpTransport extends AutoCloseable {

    /**
     * Establishes the underlying transport. Idempotent — safe to call
     * twice.
     *
     * @throws IllegalStateException when the transport can't open
     */
    void open();

    /** Releases all resources. Idempotent. */
    @Override
    void close();

    /**
     * Sends a JSON-RPC request and waits up to {@code timeout} for the
     * matching response. Caller supplies the request id (allocated via
     * {@link McpJsonRpc#allocId()}).
     *
     * @param ctx     invocation scope — used by HTTP transport for
     *                secret resolution; ignored by stdio
     * @return the {@code result} field of the response (may be null)
     * @throws McpJsonRpc.JsonRpcException when the server returns an
     *         error frame
     * @throws RuntimeException            on transport failure / timeout
     */
    @Nullable
    Object sendRequest(
            String method,
            @Nullable Map<String, Object> params,
            Duration timeout,
            ToolInvocationContext ctx);

    /**
     * Sends a JSON-RPC notification. No response is expected; failures
     * to write are propagated as {@link RuntimeException}.
     */
    void sendNotification(
            String method,
            @Nullable Map<String, Object> params,
            ToolInvocationContext ctx);

    /**
     * Registers a listener for server-pushed notifications. The MCP
     * connection layer wires this to dispatch
     * {@code notifications/tools/list_changed} → cache-invalidate, etc.
     *
     * <p>{@code null} clears the handler. Replacing an existing handler
     * is safe.
     */
    void setNotificationHandler(@Nullable Consumer<McpJsonRpc.Frame.Notification> handler);

    /** {@code true} when {@link #open} has been called and {@link #close} hasn't. */
    boolean isOpen();
}
