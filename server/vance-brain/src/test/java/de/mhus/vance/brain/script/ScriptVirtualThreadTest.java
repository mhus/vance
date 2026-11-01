package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolSource;
import de.mhus.vance.shared.permission.RecordingPermissionProvider;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The brain calls the script engine from virtual threads. The sandbox CPU limit
 * samples the CPU time of every thread inside the shared engine, and a virtual
 * thread has none — so a run, a validation or a cancel issued from one must not
 * enter a context itself ({@link PlatformThread}). The first run builds a context
 * with the limit; everything after it, on the same engine, is what used to fail.
 */
class ScriptVirtualThreadTest {

    private static Engine engine;
    /** One host access for executor and validator, as the shared bean is in production. */
    private static final HostAccess HOST_ACCESS = HostAccess.newBuilder()
            .allowAccessAnnotatedBy(HostAccess.Export.class)
            .allowImplementationsAnnotatedBy(HostAccess.Implementable.class)
            .allowMapAccess(true)
            .allowListAccess(true)
            .allowArrayAccess(true)
            .allowIterableAccess(true)
            .allowIteratorAccess(true)
            .build();

    @BeforeAll
    static void start() {
        engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @Test
    void aRunFromAVirtualThread_succeeds() throws Exception {
        GraaljsScriptExecutor exec = new GraaljsScriptExecutor(engine, HOST_ACCESS, new ScriptEngineProperties());

        ScriptResult result = onVirtualThread(() -> exec.run(request("21 * 2")));

        assertThat(String.valueOf(result.value())).isEqualTo("42");
    }

    @Test
    void aValidationFromAVirtualThread_afterALimitedRun_succeeds() throws Exception {
        GraaljsScriptExecutor exec = new GraaljsScriptExecutor(engine, HOST_ACCESS, new ScriptEngineProperties());
        JsValidationService validator = new JsValidationService(engine, HOST_ACCESS);
        // A context that carries the CPU limit now exists on the shared engine.
        exec.run(request("1 + 1"));

        JsValidationService.JsValidationResult result =
                onVirtualThread(() -> validator.validate("function add(a, b) { return a + b; }", "v.js"));

        assertThat(result.errors()).isEmpty();
        assertThat(result.ok()).isTrue();
    }

    @Test
    void platformThread_handsVirtualCallersToAPlatformThread() throws Exception {
        boolean virtualInside = onVirtualThread(
                () -> PlatformThread.call(() -> Thread.currentThread().isVirtual()));

        assertThat(virtualInside).isFalse();
    }

    @Test
    void platformThread_stillRunsTheWorkForACallerThatIsAlreadyInterrupted() throws Exception {
        // The clean-up of a cancelled run (ctx.close(true)) is issued by an
        // interrupted thread; skipping it would leave the script running.
        boolean ran = onVirtualThread(() -> {
            Thread.currentThread().interrupt();
            AtomicReference<Boolean> done = new AtomicReference<>(false);
            PlatformThread.run(() -> done.set(true));
            return done.get() && Thread.currentThread().isInterrupted();
        });

        assertThat(ran).isTrue();
    }

    @Test
    void aRunThatIsInterrupted_endsCancelled_andStopsTheScript() throws Exception {
        // What cancelling a Cortex execution does: the worker thread is interrupted
        // while it waits for the script, and the context has to be closed with
        // cancellation or the script keeps spinning in the background.
        ScriptEngineProperties props = new ScriptEngineProperties();
        props.getStatements().setMax(Long.MAX_VALUE);
        props.getStatements().setDefault(Long.MAX_VALUE);
        GraaljsScriptExecutor exec = new GraaljsScriptExecutor(engine, HOST_ACCESS, props);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                exec.run(request("let x = 0; while (true) { x++; }"));
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        worker.start();
        Thread.sleep(1500);
        worker.interrupt();
        worker.join(10_000);

        assertThat(worker.isAlive()).as("the interrupted run must return").isFalse();
        assertThat(outcome.get()).isInstanceOf(ScriptExecutionException.class);
        assertThat(((ScriptExecutionException) outcome.get()).errorClass())
                .isEqualTo(ScriptExecutionException.ErrorClass.CANCELLED);
    }

    @Test
    void platformThread_runsInlineOnAPlatformThread() throws Exception {
        Thread caller = Thread.currentThread();

        Thread inside = PlatformThread.call(Thread::currentThread);

        assertThat(inside).isSameAs(caller);
    }

    @Test
    void platformThread_rethrowsTheFailureOfTheWorkAsItself() {
        IllegalStateException boom = new IllegalStateException("boom");

        Throwable thrown =
                org.assertj.core.api.Assertions.catchThrowable(() -> onVirtualThread(() -> PlatformThread.call(() -> {
                    throw boom;
                })));

        assertThat(thrown).isSameAs(boom);
    }

    private static <T> T onVirtualThread(java.util.concurrent.Callable<T> work) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                value.set(work.call());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.join();
        Throwable t = failure.get();
        if (t instanceof Exception e) {
            throw e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return value.get();
    }

    private static ScriptRequest request(String code) {
        return new ScriptRequest("js", code, "test", tools(), Duration.ofSeconds(30));
    }

    private static ContextToolsApi tools() {
        ToolSource src = mock(ToolSource.class);
        when(src.sourceId()).thenReturn("test");
        when(src.tools(any())).thenReturn(List.<Tool>of());
        when(src.find(any(), any())).thenReturn(Optional.empty());
        ToolDispatcher dispatcher = new ToolDispatcher(
                List.of(src),
                new RecordingPermissionProvider().service(),
                mock(de.mhus.vance.brain.agrajag.AgrajagChecker.class),
                mock(de.mhus.vance.shared.toolhealth.ToolHealthService.class),
                mock(de.mhus.vance.shared.team.TeamService.class),
                mock(de.mhus.vance.brain.tools.ToolGuardGate.class));
        ToolInvocationContext ctx = new ToolInvocationContext("acme", "proj-1", "sess-1", "proc-1", "alice");
        return new ContextToolsApi(dispatcher, ctx, Set.of());
    }
}
