package de.mhus.vance.brain.script.cortex;

import de.mhus.vance.api.scripts.ScriptExecutionStatus;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Asynchronous script-execution runtime for the Script Cortex Web-UI.
 *
 * <p>Wraps {@link de.mhus.vance.brain.script.GraaljsScriptExecutor} into
 * a fire-and-forget service:
 *
 * <ul>
 *   <li>{@link #start} returns immediately with an {@code executionId};
 *       evaluation runs on a cached thread pool.</li>
 *   <li>{@link #cancel} interrupts the worker; the underlying executor
 *       catches it, closes the Polyglot Context, and the run terminates
 *       with {@link ScriptExecutionException.ErrorClass#CANCELLED}.</li>
 *   <li>Captured {@code console.*} lines are pushed to the
 *       {@link ScriptExecutionWsRegistry} (live) AND buffered in a
 *       bounded ring for status-polling fallback ({@link #getStatus}).</li>
 *   <li>Finished executions stay in the registry for {@code RETENTION}
 *       (5min) so a late {@link #getStatus} can still grab the result.</li>
 * </ul>
 *
 * <p>v1 deliberately gives the script <b>no tools</b> — only
 * {@code console.*} and the {@code args} binding. The Cortex is an
 * editor / sandbox, not an agent runtime. A future iteration can plumb
 * a scoped {@link ContextToolsApi} through {@link StartRequest}.
 */
@Service
@Slf4j
public class ScriptCortexExecutionService {

    private static final Duration RETENTION = Duration.ofMinutes(5);
    private static final int LOG_BUFFER_LIMIT = 10_000;

    private final de.mhus.vance.brain.script.GraaljsScriptExecutor executor;
    private final ScriptExecutionWsRegistry wsRegistry;
    private final ToolDispatcher toolDispatcher;

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cortex-script-" + UUID.randomUUID().toString().substring(0, 8));
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Execution> executions = new ConcurrentHashMap<>();

    public ScriptCortexExecutionService(
            de.mhus.vance.brain.script.GraaljsScriptExecutor executor,
            ScriptExecutionWsRegistry wsRegistry,
            ToolDispatcher toolDispatcher) {
        this.executor = executor;
        this.wsRegistry = wsRegistry;
        this.toolDispatcher = toolDispatcher;
    }

    /**
     * Submit a script for asynchronous execution. Returns the
     * {@code executionId} the caller passes to
     * {@link ScriptExecutionSubscribeHandler#type()} subscriptions and
     * to {@link #cancel} / {@link #getStatus}.
     */
    public String start(StartRequest req) {
        String executionId = UUID.randomUUID().toString();
        Execution ex = new Execution(executionId, Instant.now());
        executions.put(executionId, ex);

        wsRegistry.pushStarted(executionId, ex.startedAt.toEpochMilli());

        Future<?> future = pool.submit(() -> runExecution(executionId, req));
        ex.future = future;
        return executionId;
    }

    private void runExecution(String executionId, StartRequest req) {
        Execution ex = executions.get(executionId);
        if (ex == null) return;

        ScriptLogSink sink = (stream, line) -> {
            String composed = "[" + stream + "] " + line;
            synchronized (ex.logBuffer) {
                ex.logBuffer.add(composed);
                while (ex.logBuffer.size() > LOG_BUFFER_LIMIT) {
                    ex.logBuffer.removeFirst();
                }
            }
            wsRegistry.pushLog(executionId, stream, line);
        };
        ScriptCortexConsole console = new ScriptCortexConsole(sink);

        Map<String, @Nullable Object> bindings = new LinkedHashMap<>();
        bindings.put("console", console);
        bindings.put("args", req.args == null ? Map.of() : req.args);

        // No-tools surface: the dispatcher is real, but the allow-set is
        // empty → ContextToolsApi.invoke rejects every name. The script
        // can still inspect `vance.context` and `vance.log` (parents in
        // VanceScriptApi), it just cannot dispatch tools.
        ToolInvocationContext scope = new ToolInvocationContext(
                req.tenantId,
                req.projectId,
                /*sessionId*/ null,
                /*processId*/ null,
                req.userId);
        ContextToolsApi tools = new ContextToolsApi(toolDispatcher, scope, java.util.Set.of());

        Duration timeout = req.timeoutMs == null
                ? Duration.ofSeconds(30)
                : Duration.ofMillis(Math.max(1, req.timeoutMs));

        de.mhus.vance.brain.script.ScriptRequest scriptReq =
                new de.mhus.vance.brain.script.ScriptRequest(
                        "js",
                        req.code,
                        req.sourceName == null ? "cortex-execution.js" : req.sourceName,
                        tools,
                        timeout,
                        bindings,
                        /*recipeName*/ null);

        // Tee `vance.log.*` into the same sink as `console.*` so the
        // Execute dialog surfaces both channels. InheritableThreadLocal
        // propagates into the GraalJS watchdog thread when it's spun up.
        de.mhus.vance.brain.script.VanceScriptApi.setActiveLogTee(sink::accept);

        try {
            de.mhus.vance.brain.script.ScriptResult result = executor.run(scriptReq);
            ex.complete(result.value(), null, "finished");
            wsRegistry.pushFinished(
                    executionId,
                    result.value(),
                    ex.endedAt == null ? System.currentTimeMillis() : ex.endedAt.toEpochMilli(),
                    ex.durationMs());
        } catch (ScriptExecutionException e) {
            boolean cancelled = e.errorClass() == ScriptExecutionException.ErrorClass.CANCELLED;
            String state = cancelled ? "cancelled" : "failed";
            ex.complete(null, e.getMessage(), state);
            long endedAtMs = ex.endedAt == null
                    ? System.currentTimeMillis() : ex.endedAt.toEpochMilli();
            long durationMs = ex.durationMs();
            if (cancelled) {
                wsRegistry.pushCancelled(executionId, endedAtMs, durationMs);
            } else {
                wsRegistry.pushFailed(executionId, e.getMessage(), endedAtMs, durationMs);
            }
        } catch (RuntimeException e) {
            log.warn("Cortex script execution '{}' raised unexpected: {}",
                    executionId, e.toString(), e);
            ex.complete(null, e.toString(), "failed");
            wsRegistry.pushFailed(
                    executionId,
                    e.toString(),
                    ex.endedAt == null ? System.currentTimeMillis() : ex.endedAt.toEpochMilli(),
                    ex.durationMs());
        } finally {
            de.mhus.vance.brain.script.VanceScriptApi.clearActiveLogTee();
            scheduleEviction(executionId);
        }
    }

    /**
     * Cancels a running execution. Returns {@code true} when the
     * execution existed and was running; {@code false} when it had
     * already finished or never existed.
     */
    public boolean cancel(String executionId) {
        Execution ex = executions.get(executionId);
        if (ex == null) return false;
        if (ex.future == null) return false;
        if (!"running".equals(ex.state)) return false;
        boolean cancelled = ex.future.cancel(true);
        if (cancelled) {
            // The actual state transition happens on the worker thread
            // when the InterruptedException propagates out of run() —
            // see runExecution's catch. Logging here is purely for
            // operator visibility.
            log.info("Cortex script execution '{}' cancel requested", executionId);
        }
        return cancelled;
    }

    /** Snapshot of one execution. Returns {@code null} when unknown. */
    public Optional<ScriptExecutionStatus> getStatus(String executionId) {
        Execution ex = executions.get(executionId);
        if (ex == null) return Optional.empty();
        List<String> snapshot;
        synchronized (ex.logBuffer) {
            snapshot = List.copyOf(ex.logBuffer);
        }
        return Optional.of(ScriptExecutionStatus.builder()
                .executionId(executionId)
                .state(ex.state)
                .startedAtMs(ex.startedAt.toEpochMilli())
                .endedAtMs(ex.endedAt == null ? null : ex.endedAt.toEpochMilli())
                .durationMs(ex.endedAt == null ? null : ex.durationMs())
                .resultValue(ex.resultValue)
                .errorMessage(ex.errorMessage)
                .logBuffer(snapshot)
                .build());
    }

    private void scheduleEviction(String executionId) {
        // Cheap eviction: a daemon thread sleeps RETENTION then drops
        // the entry. We expect at most a few hundred concurrent ids; a
        // ScheduledExecutorService would be cleaner but it's overkill
        // for the throughput.
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(RETENTION.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            executions.remove(executionId);
        }, "cortex-eviction-" + executionId.substring(0, 8));
        t.setDaemon(true);
        t.start();
    }

    /** Builder shape for {@link #start}. Kept inside the service so the
     *  caller doesn't need to know about {@code ScriptRequest} or
     *  {@code ContextToolsApi} construction. */
    public static final class StartRequest {
        public final String tenantId;
        public final @Nullable String projectId;
        public final @Nullable String userId;
        public final String code;
        public final @Nullable String sourceName;
        public final Map<String, Object> args;
        public final @Nullable Long timeoutMs;

        public StartRequest(
                String tenantId,
                @Nullable String projectId,
                @Nullable String userId,
                String code,
                @Nullable String sourceName,
                @Nullable Map<String, Object> args,
                @Nullable Long timeoutMs) {
            this.tenantId = tenantId;
            this.projectId = projectId;
            this.userId = userId;
            this.code = code;
            this.sourceName = sourceName;
            this.args = args == null ? Map.of() : args;
            this.timeoutMs = timeoutMs;
        }
    }

    /** Per-execution state, held in {@link #executions}. */
    private static final class Execution {
        final String executionId;
        final Instant startedAt;
        @Nullable Future<?> future;
        volatile String state = "running";
        volatile @Nullable Instant endedAt;
        volatile @Nullable Object resultValue;
        volatile @Nullable String errorMessage;
        final Deque<String> logBuffer = new ArrayDeque<>();

        Execution(String executionId, Instant startedAt) {
            this.executionId = executionId;
            this.startedAt = startedAt;
        }

        void complete(@Nullable Object resultValue, @Nullable String errorMessage, String state) {
            this.resultValue = resultValue;
            this.errorMessage = errorMessage;
            this.state = state;
            this.endedAt = Instant.now();
        }

        long durationMs() {
            if (endedAt == null) return System.currentTimeMillis() - startedAt.toEpochMilli();
            return endedAt.toEpochMilli() - startedAt.toEpochMilli();
        }
    }
}
