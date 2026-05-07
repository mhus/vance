package de.mhus.vance.brain.toolpack.mcp;

import de.mhus.vance.brain.toolpack.core.McpJsonRpc;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * MCP transport over a subprocess's stdin/stdout. Per MCP spec each
 * JSON-RPC frame is a single line of JSON terminated by {@code \n}.
 * The reader thread parses incoming lines, correlates responses by
 * id, and dispatches notifications to the registered handler.
 *
 * <p>Process lifecycle is owned by this transport: {@link #open}
 * spawns, {@link #close} terminates. Output (stderr) is logged at
 * info level so MCP-server diagnostics surface in the brain log.
 *
 * <p>Auto-reconnect is <b>not</b> built in — the
 * {@link McpConnection} layer detects unhealthy state (e.g. send
 * fails) and rebuilds the transport via {@link McpConnectionPool}.
 */
@Slf4j
public final class McpStdioTransport implements McpTransport {

    private final McpConfig config;
    private final McpJsonRpc rpc;
    private final ConcurrentHashMap<Long, CompletableFuture<McpJsonRpc.Frame.Response>> pending
            = new ConcurrentHashMap<>();

    private @Nullable Process process;
    private @Nullable BufferedWriter stdin;
    private @Nullable Thread readerThread;
    private @Nullable Thread stderrThread;
    private @Nullable Consumer<McpJsonRpc.Frame.Notification> notificationHandler;
    private volatile boolean open;

    public McpStdioTransport(McpConfig config, McpJsonRpc rpc) {
        this.config = config;
        this.rpc = rpc;
    }

    @Override
    public synchronized void open() {
        if (open) return;
        if (config.transport() != McpConfig.Transport.STDIO) {
            throw new IllegalStateException(
                    "McpStdioTransport: config.transport must be STDIO, got " + config.transport());
        }
        ProcessBuilder pb = new ProcessBuilder(config.command());
        if (config.cwd() != null) pb.directory(new File(config.cwd()));
        pb.environment().putAll(config.env());
        pb.redirectErrorStream(false);
        try {
            this.process = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "McpStdioTransport: failed to spawn '" + String.join(" ", config.command())
                            + "': " + e.getMessage(), e);
        }
        Process p = this.process;
        this.stdin = new BufferedWriter(new OutputStreamWriter(
                p.getOutputStream(), StandardCharsets.UTF_8));

        BufferedReader stdout = new BufferedReader(new InputStreamReader(
                p.getInputStream(), StandardCharsets.UTF_8));
        this.readerThread = new Thread(() -> readerLoop(stdout), "mcp-stdio-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();

        BufferedReader stderr = new BufferedReader(new InputStreamReader(
                p.getErrorStream(), StandardCharsets.UTF_8));
        this.stderrThread = new Thread(() -> stderrLoop(stderr), "mcp-stdio-stderr");
        this.stderrThread.setDaemon(true);
        this.stderrThread.start();

        this.open = true;
        log.info("McpStdioTransport opened: pid={} cmd={}", p.pid(), config.command());
    }

    @Override
    public synchronized void close() {
        if (!open) return;
        open = false;
        // Fail any in-flight requests so callers don't hang.
        for (CompletableFuture<McpJsonRpc.Frame.Response> f : pending.values()) {
            f.completeExceptionally(new IllegalStateException("MCP stdio transport closed"));
        }
        pending.clear();
        try {
            if (stdin != null) stdin.close();
        } catch (IOException ignored) { /* best-effort */ }
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        if (readerThread != null) readerThread.interrupt();
        if (stderrThread != null) stderrThread.interrupt();
        log.info("McpStdioTransport closed: pid was={}",
                process == null ? -1 : process.pid());
    }

    @Override
    public boolean isOpen() {
        return open && process != null && process.isAlive();
    }

    @Override
    public @Nullable Object sendRequest(
            String method,
            @Nullable Map<String, Object> params,
            Duration timeout,
            ToolInvocationContext ctx) {
        if (!isOpen()) {
            throw new IllegalStateException("MCP stdio transport not open");
        }
        long id = rpc.allocId();
        String frame = rpc.buildRequest(id, method, params);
        CompletableFuture<McpJsonRpc.Frame.Response> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            writeLine(frame);
            McpJsonRpc.Frame.Response response;
            try {
                response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                throw new IllegalStateException(
                        "MCP request timed out after " + timeout + " — method=" + method);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "MCP request interrupted — method=" + method);
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new IllegalStateException(
                        "MCP request failed — method=" + method + ": " + cause.getMessage(), cause);
            }
            if (response.error() != null) {
                throw McpJsonRpc.JsonRpcException.fromMap(response.error());
            }
            return response.result();
        } finally {
            pending.remove(id);
        }
    }

    @Override
    public void sendNotification(
            String method, @Nullable Map<String, Object> params, ToolInvocationContext ctx) {
        if (!isOpen()) {
            throw new IllegalStateException("MCP stdio transport not open");
        }
        writeLine(rpc.buildNotification(method, params));
    }

    @Override
    public synchronized void setNotificationHandler(
            @Nullable Consumer<McpJsonRpc.Frame.Notification> handler) {
        this.notificationHandler = handler;
    }

    private synchronized void writeLine(String json) {
        if (stdin == null) {
            throw new IllegalStateException("MCP stdio transport: stdin is null");
        }
        try {
            stdin.write(json);
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            throw new IllegalStateException("MCP stdio write failed: " + e.getMessage(), e);
        }
    }

    private void readerLoop(BufferedReader reader) {
        try {
            String line;
            while (open && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                dispatchFrame(line.trim());
            }
        } catch (IOException e) {
            if (open) {
                log.warn("McpStdioTransport reader IO error: {}", e.toString());
            }
        } finally {
            // Reader EOF — most often the subprocess died. Surface to all
            // pending futures so callers don't hang forever.
            IllegalStateException eof = new IllegalStateException(
                    "MCP stdio subprocess closed stdout");
            for (CompletableFuture<McpJsonRpc.Frame.Response> f : pending.values()) {
                f.completeExceptionally(eof);
            }
        }
    }

    private void dispatchFrame(String line) {
        McpJsonRpc.Frame frame;
        try {
            frame = McpJsonRpc.parse(line);
        } catch (RuntimeException e) {
            log.warn("McpStdioTransport: malformed JSON-RPC frame, dropping: {} -- '{}'",
                    e.getMessage(), truncate(line));
            return;
        }
        if (frame instanceof McpJsonRpc.Frame.Response r) {
            CompletableFuture<McpJsonRpc.Frame.Response> waiting = pending.remove(r.id());
            if (waiting == null) {
                log.debug("McpStdioTransport: response for unknown id={}", r.id());
                return;
            }
            waiting.complete(r);
            return;
        }
        if (frame instanceof McpJsonRpc.Frame.Notification n) {
            Consumer<McpJsonRpc.Frame.Notification> h = notificationHandler;
            if (h != null) {
                try { h.accept(n); }
                catch (RuntimeException e) {
                    log.warn("McpStdioTransport: notification handler threw for method={}: {}",
                            n.method(), e.toString());
                }
            }
            return;
        }
        // Server-initiated request — MCP allows this for sampling, but
        // v1 doesn't support it. Log and ignore.
        if (frame instanceof McpJsonRpc.Frame.Request r) {
            log.debug("McpStdioTransport: ignoring server-initiated request method={} id={}",
                    r.method(), r.id());
        }
    }

    private void stderrLoop(BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[mcp-stderr] {}", line);
            }
        } catch (IOException ignored) { /* process exited */ }
    }

    private static String truncate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 197) + "...";
    }
}
