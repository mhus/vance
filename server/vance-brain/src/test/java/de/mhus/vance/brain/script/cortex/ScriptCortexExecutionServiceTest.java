package de.mhus.vance.brain.script.cortex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.api.scripts.ScriptExecutionStatus;
import de.mhus.vance.brain.script.GraaljsScriptExecutor;
import de.mhus.vance.brain.script.ScriptEngineProperties;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.ws.WebSocketSender;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the async execute pipeline without booting Spring or a
 * MongoDB container. Wires a real {@link GraaljsScriptExecutor} (with
 * its own short-lived Engine) plus mocked WS/dispatcher dependencies,
 * runs a tiny script, and verifies the result + captured console
 * output.
 *
 * <p>Mirrors the integration probe in {@code qa/ai-test/}
 * (ScriptCortexExecuteE2ETest) for the parts that don't need a real
 * DocumentService — keeps the fast feedback loop green even when
 * Docker / testcontainers aren't available locally.
 */
class ScriptCortexExecutionServiceTest {

    private Engine engine;
    private ScriptCortexExecutionService service;
    private ScriptExecutionWsRegistry wsRegistry;

    @BeforeEach
    void setUp() {
        engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        GraaljsScriptExecutor executor = new GraaljsScriptExecutor(engine, new ScriptEngineProperties());
        wsRegistry = new ScriptExecutionWsRegistry(mock(WebSocketSender.class));
        ToolDispatcher dispatcher = mock(ToolDispatcher.class);
        service = new ScriptCortexExecutionService(executor, wsRegistry, dispatcher);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void start_runsScriptWithArgsAndCapturesConsoleOutput() throws Exception {
        String code = """
                console.log('start', args.x);
                var v = args.x + args.y;
                console.log('done', v);
                v;
                """;

        ScriptCortexExecutionService.StartRequest req = new ScriptCortexExecutionService.StartRequest(
                "acme", "p1", "wile.coyote", code, "sum.js", Map.of("x", 5, "y", 7), 2_000L);
        String id = service.start(req);

        boolean done = waitFor(
                Duration.ofSeconds(10),
                () -> service.getStatus(id)
                        .map(s -> !"running".equals(s.getState()))
                        .orElse(false));
        assertThat(done).isTrue();

        ScriptExecutionStatus status = service.getStatus(id).orElseThrow();
        assertThat(status.getState()).isEqualTo("finished");
        assertThat(status.getResultValue()).isInstanceOfAny(Long.class, Integer.class, Double.class);
        assertThat(((Number) status.getResultValue()).longValue()).isEqualTo(12L);
        assertThat(status.getLogBuffer()).anyMatch(l -> l.contains("start")).anyMatch(l -> l.contains("done 12"));
    }

    @Test
    void start_failingScriptLandsInFailedStateWithMessage() throws Exception {
        // Reference an undefined identifier → ReferenceError.
        ScriptCortexExecutionService.StartRequest req = new ScriptCortexExecutionService.StartRequest(
                "acme", "p1", "wile.coyote", "thisDoesNotExist.callIt();", "boom.js", Map.of(), 2_000L);
        String id = service.start(req);

        boolean done = waitFor(
                Duration.ofSeconds(10),
                () -> service.getStatus(id)
                        .map(s -> !"running".equals(s.getState()))
                        .orElse(false));
        assertThat(done).isTrue();

        ScriptExecutionStatus status = service.getStatus(id).orElseThrow();
        assertThat(status.getState()).isEqualTo("failed");
        assertThat(status.getErrorMessage()).isNotBlank();
    }

    @Test
    void getStatus_unknownIdReturnsEmpty() {
        Optional<ScriptExecutionStatus> status = service.getStatus("not-a-real-id");
        assertThat(status).isEmpty();
    }

    @Test
    void cancel_unknownIdReturnsFalse() {
        assertThat(service.cancel("never-started")).isFalse();
    }

    @Test
    void cancel_whileTheToolSurfaceIsStillBeingResolved_endsTheExecutionCancelled() throws Exception {
        // The worker reports 'running' as soon as it starts, well before the script
        // does. A cancel in that window interrupts the preparation, which then
        // fails — and that failure used to leave the execution 'running' forever.
        java.util.concurrent.CountDownLatch inPolicy = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ScriptCortexToolPolicy policy = mock(ScriptCortexToolPolicy.class);
        org.mockito.Mockito.when(policy.availableTools(
                        org.mockito.ArgumentMatchers.any(de.mhus.vance.toolpack.ToolInvocationContext.class)))
                .thenAnswer(inv -> {
                    inPolicy.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException("interrupted while resolving tools", e);
                    }
                    throw new IllegalStateException("resolving tools failed");
                });
        GraaljsScriptExecutor executor = new GraaljsScriptExecutor(engine, new ScriptEngineProperties());
        ScriptCortexExecutionService svc =
                new ScriptCortexExecutionService(executor, wsRegistry, mock(ToolDispatcher.class), policy);

        String id = svc.start(new ScriptCortexExecutionService.StartRequest(
                "acme", "p1", "wile.coyote", "1 + 1", "x.js", Map.of(), 5_000L));
        assertThat(inPolicy.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(svc.cancel(id)).isTrue();
        release.countDown();

        boolean done = waitFor(
                Duration.ofSeconds(10),
                () -> svc.getStatus(id)
                        .map(st -> !"running".equals(st.getState()))
                        .orElse(false));
        assertThat(done).as("the execution must reach a terminal state").isTrue();
        assertThat(svc.getStatus(id).orElseThrow().getState()).isEqualTo("cancelled");
    }

    @Test
    void anExecutionWhoseWorkerDiesUnexpectedly_doesNotStayRunning() throws Exception {
        // A failure that none of the catch blocks names (here an Error) must still
        // end in a terminal state.
        ScriptCortexToolPolicy policy = mock(ScriptCortexToolPolicy.class);
        org.mockito.Mockito.when(policy.availableTools(
                        org.mockito.ArgumentMatchers.any(de.mhus.vance.toolpack.ToolInvocationContext.class)))
                .thenThrow(new AssertionError("not an Exception"));
        GraaljsScriptExecutor executor = new GraaljsScriptExecutor(engine, new ScriptEngineProperties());
        ScriptCortexExecutionService svc =
                new ScriptCortexExecutionService(executor, wsRegistry, mock(ToolDispatcher.class), policy);

        String id = svc.start(new ScriptCortexExecutionService.StartRequest(
                "acme", "p1", "wile.coyote", "1 + 1", "x.js", Map.of(), 5_000L));

        boolean done = waitFor(
                Duration.ofSeconds(10),
                () -> svc.getStatus(id)
                        .map(st -> !"running".equals(st.getState()))
                        .orElse(false));
        assertThat(done).isTrue();
        assertThat(svc.getStatus(id).orElseThrow().getState()).isEqualTo("failed");
    }

    private static boolean waitFor(Duration timeout, java.util.function.BooleanSupplier check)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return false;
    }
}
