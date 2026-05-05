package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.brain.tools.ToolSource;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.RecordingPermissionResolver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ScriptExecutorTest {

    private static Engine engine;
    private static ScriptExecutor executor;

    @BeforeAll
    static void start() {
        engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        executor = new GraaljsScriptExecutor(engine);
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static ContextToolsApi unrestrictedTools(Tool... tools) {
        return toolsApi(Set.of(), tools);
    }

    private static ContextToolsApi toolsApi(Set<String> allowed, Tool... tools) {
        ToolSource src = mock(ToolSource.class);
        when(src.sourceId()).thenReturn("test");
        when(src.tools(any())).thenReturn(List.of(tools));
        for (Tool t : tools) {
            when(src.find(eq(t.name()), any())).thenReturn(Optional.of(t));
        }
        ToolDispatcher dispatcher = new ToolDispatcher(
                List.of(src), new PermissionService(new RecordingPermissionResolver()));
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj-1", "sess-1", "proc-1", "alice");
        return new ContextToolsApi(dispatcher, ctx, allowed);
    }

    private static ScriptResult eval(String code, ContextToolsApi tools) {
        return executor.run(new ScriptRequest("js", code, "test", tools, Duration.ofSeconds(5)));
    }

    private static ScriptResult eval(String code) {
        return eval(code, unrestrictedTools());
    }

    // ------------------------------------------------------------------
    // 7.1 Happy path
    // ------------------------------------------------------------------

    @Test
    void run_returnsPrimitiveValue() {
        assertThat(eval("42;").value()).isEqualTo(42L);
    }

    @Test
    void run_returnsString() {
        assertThat(eval("'hello';").value()).isEqualTo("hello");
    }

    @Test
    void run_returnsNullForVoidScript() {
        assertThat(eval("var x = 1;").value()).isNull();
    }

    @Test
    void run_executesMultipleStatements() {
        String code = "var sum = 0; for (var i = 1; i <= 10; i++) sum += i; sum;";
        assertThat(eval(code).value()).isEqualTo(55L);
    }

    @Test
    void run_durationIsMeasured() {
        assertThat(eval("1;").duration()).isPositive();
    }

    @Test
    void run_returnsMappedObject() {
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) eval("({a: 1, b: 'x'});").value();
        assertThat(value).containsEntry("a", 1L).containsEntry("b", "x");
    }

    @Test
    void run_returnsMappedArray() {
        @SuppressWarnings("unchecked")
        List<Object> value = (List<Object>) eval("[1, 2, 3];").value();
        assertThat(value).containsExactly(1L, 2L, 3L);
    }

    // ------------------------------------------------------------------
    // 7.2 Sandbox must-fail
    // ------------------------------------------------------------------

    @Test
    void run_deniesJavaTypeAccess() {
        assertThatThrownBy(() -> eval("Java.type('java.lang.System');"))
                .isInstanceOf(ScriptExecutionException.class);
    }

    @Test
    void run_deniesProcessExec() {
        assertThatThrownBy(() ->
                eval("Java.type('java.lang.Runtime').getRuntime().exec('echo');"))
                .isInstanceOf(ScriptExecutionException.class);
    }

    @Test
    void run_deniesEnvAccess() {
        // process is not defined in a polyglot context with EnvironmentAccess.NONE
        assertThat(eval("typeof process;").value()).isEqualTo("undefined");
    }

    // ------------------------------------------------------------------
    // 7.3 Resource limits / timeout
    // ------------------------------------------------------------------

    @Test
    void run_aborts_onWallClockTimeout() {
        ScriptRequest req = new ScriptRequest(
                "js",
                "while (true) {}",
                "test",
                unrestrictedTools(),
                Duration.ofMillis(200));
        assertThatThrownBy(() -> executor.run(req))
                .isInstanceOf(ScriptExecutionException.class)
                .satisfies(t -> assertThat(((ScriptExecutionException) t).errorClass())
                        .isIn(
                                ScriptExecutionException.ErrorClass.TIMEOUT,
                                ScriptExecutionException.ErrorClass.RESOURCE_EXHAUSTED,
                                ScriptExecutionException.ErrorClass.CANCELLED));
    }

    // ------------------------------------------------------------------
    // 7.4 Host API — vance.context, vance.tools, vance.log
    // ------------------------------------------------------------------

    @Test
    void vanceContext_isReadable() {
        Object value = eval("vance.context.tenantId;").value();
        assertThat(value).isEqualTo("acme");
    }

    @Test
    void vanceContext_projectId_isReadable() {
        assertThat(eval("vance.context.projectId;").value()).isEqualTo("proj-1");
    }

    @Test
    void vanceTools_call_dispatchesToToolDispatcher() {
        Tool sumTool = new Tool() {
            @Override public String name() { return "sum_two"; }
            @Override public String description() { return ""; }
            @Override public boolean primary() { return true; }
            @Override public Map<String, Object> paramsSchema() { return Map.of(); }
            @Override
            public Map<String, Object> invoke(Map<String, Object> p, ToolInvocationContext ctx) {
                long a = ((Number) p.get("a")).longValue();
                long b = ((Number) p.get("b")).longValue();
                return Map.of("sum", a + b);
            }
        };
        Object value = eval(
                "vance.tools.call('sum_two', {a: 3, b: 4}).sum;",
                unrestrictedTools(sumTool)).value();
        assertThat(value).isEqualTo(7L);
    }

    @Test
    void vanceTools_call_unknownTool_throwsInJs_andCanBeCaught() {
        String code =
                "try { vance.tools.call('nope', {}); 'no-throw'; } catch (e) { 'caught'; }";
        assertThat(eval(code).value()).isEqualTo("caught");
    }

    @Test
    void vanceTools_call_respectsAllowedSet() {
        Tool free = simpleTool("free", Map.of("ok", true));
        Tool blocked = simpleTool("blocked", Map.of("ok", true));
        ContextToolsApi restricted = toolsApi(Set.of("free"), free, blocked);

        // "free" is allowed
        assertThat(eval("!!vance.tools.call('free', {}).ok;", restricted).value())
                .isEqualTo(true);

        // "blocked" is not in the allow-set — must throw
        String code = "try { vance.tools.call('blocked', {}); 'no-throw'; } "
                + "catch (e) { 'caught'; }";
        assertThat(eval(code, restricted).value()).isEqualTo("caught");
    }

    @Test
    void vanceLog_info_doesNotThrow() {
        eval("vance.log.info('hello', {a: 1});");
    }

    // ------------------------------------------------------------------
    // 7.5 Cross-run isolation
    // ------------------------------------------------------------------

    @Test
    void run_doesNotShareGlobalsBetweenRuns() {
        eval("globalThis.vanceTestFoo = 1;");
        Object second = eval("typeof globalThis.vanceTestFoo;").value();
        assertThat(second).isEqualTo("undefined");
    }

    @Test
    void run_doesNotShareHostBindingsBetweenRuns() {
        // First run shadows vance — should not affect the second run
        eval("globalThis.vance = {context: {tenantId: 'evil'}};");
        Object tenant = eval("vance.context.tenantId;").value();
        assertThat(tenant).isEqualTo("acme");
    }

    // ------------------------------------------------------------------
    // 7.6 Error mapping
    // ------------------------------------------------------------------

    @Test
    void run_uncaughtJsError_isWrapped() {
        assertThatThrownBy(() -> eval("throw new Error('boom');"))
                .isInstanceOf(ScriptExecutionException.class)
                .satisfies(t -> assertThat(((ScriptExecutionException) t).errorClass())
                        .isEqualTo(ScriptExecutionException.ErrorClass.GUEST_EXCEPTION));
    }

    @Test
    void run_javaException_inHostMethod_isMappedToJsError() {
        Tool boom = new Tool() {
            @Override public String name() { return "boom"; }
            @Override public String description() { return ""; }
            @Override public boolean primary() { return true; }
            @Override public Map<String, Object> paramsSchema() { return Map.of(); }
            @Override
            public Map<String, Object> invoke(Map<String, Object> p, ToolInvocationContext ctx) {
                throw new ToolException("synthetic failure");
            }
        };
        String code =
                "try { vance.tools.call('boom', {}); 'no-throw'; } catch (e) { e.message; }";
        Object value = eval(code, unrestrictedTools(boom)).value();
        assertThat(value).asString().contains("synthetic failure");
    }

    private static Tool simpleTool(String name, Map<String, Object> result) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return ""; }
            @Override public boolean primary() { return true; }
            @Override public Map<String, Object> paramsSchema() { return Map.of(); }
            @Override
            public Map<String, Object> invoke(Map<String, Object> p, ToolInvocationContext ctx) {
                return result;
            }
        };
    }
}
