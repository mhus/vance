package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
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
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * End-to-end check that {@link GraaljsScriptExecutor} applies the sandbox limits
 * from {@code vance.script.limits.*} to real script runs. The heap case is skipped
 * on runtimes without heap-limit support (anything but Oracle GraalVM).
 */
class GraaljsScriptExecutorSandboxLimitsTest {

    private static Engine engine;

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
    void run_busyLoopPastCpuLimit_failsWithResourceExhausted() {
        ScriptEngineProperties props = roomyProps();
        props.getLimits().setMaxCpuTime(Duration.ofSeconds(1));
        GraaljsScriptExecutor exec = new GraaljsScriptExecutor(engine, props);

        assertThatThrownBy(() -> exec.run(request("let x = 0; while (true) { x++; }")))
                .isInstanceOf(ScriptExecutionException.class)
                .satisfies(e -> assertThat(((ScriptExecutionException) e).errorClass())
                        .isEqualTo(ScriptExecutionException.ErrorClass.RESOURCE_EXHAUSTED));
    }

    @Test
    void run_headerNarrowsCpuLimit_failsWithResourceExhausted() {
        GraaljsScriptExecutor exec = new GraaljsScriptExecutor(engine, roomyProps());
        String code = """
                /**
                 * @maxCpuTime 1s
                 */
                let x = 0; while (true) { x++; }
                """;

        assertThatThrownBy(() -> exec.run(request(code)))
                .isInstanceOf(ScriptExecutionException.class)
                .satisfies(e -> assertThat(((ScriptExecutionException) e).errorClass())
                        .isEqualTo(ScriptExecutionException.ErrorClass.RESOURCE_EXHAUSTED));
    }

    @Test
    void run_allocationPastHeapLimit_failsWithResourceExhausted() {
        ScriptEngineProperties props = roomyProps();
        props.getLimits().setMaxHeapMemory(DataSize.ofMegabytes(32));
        // Backstop so a non-enforced heap limit ends the loop instead of the test JVM.
        props.getLimits().setMaxCpuTime(Duration.ofSeconds(20));
        GraaljsScriptExecutor exec = new GraaljsScriptExecutor(engine, props);
        assumeThat(ScriptSandboxLimits.detect(engine, new ScriptEngineConfig().scriptHostAccess(), props)
                        .heapLimitSupported())
                .as("heap limit needs the Oracle GraalVM runtime")
                .isTrue();

        assertThatThrownBy(() -> exec.run(request("const a = []; while (true) { a.push({ s: 'x' + a.length }); }")))
                .isInstanceOf(ScriptExecutionException.class)
                .satisfies(e -> assertThat(((ScriptExecutionException) e).errorClass())
                        .isEqualTo(ScriptExecutionException.ErrorClass.RESOURCE_EXHAUSTED))
                .hasMessageContaining("heap memory");
    }

    @Test
    void run_stringPastLengthLimit_raisesRangeError() {
        ScriptEngineProperties props = roomyProps();
        props.getLimits().setMaxStringLength(1_000_000);
        GraaljsScriptExecutor exec = new GraaljsScriptExecutor(engine, props);

        assertThatThrownBy(() -> exec.run(request("'x'.repeat(2000000).length")))
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("Invalid string length");
    }

    @Test
    void run_withinLimits_returnsResult() {
        GraaljsScriptExecutor exec = new GraaljsScriptExecutor(engine, roomyProps());

        ScriptResult result = exec.run(request("const a = []; for (let i = 0; i < 1000; i++) a.push(i); a.length"));

        assertThat(result.value()).isEqualTo(1000L);
    }

    /**
     * Statement limit out of reach, so a tripped run can only be a sandbox limit —
     * the CPU tests need no message check (Temurin words it differently).
     */
    private static ScriptEngineProperties roomyProps() {
        ScriptEngineProperties props = new ScriptEngineProperties();
        props.getStatements().setMax(Long.MAX_VALUE);
        props.getStatements().setDefault(Long.MAX_VALUE);
        return props;
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
