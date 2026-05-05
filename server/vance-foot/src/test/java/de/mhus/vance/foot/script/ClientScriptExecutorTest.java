package de.mhus.vance.foot.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.foot.tools.ClientTool;
import de.mhus.vance.foot.tools.ClientToolService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClientScriptExecutorTest {

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

    private static GraaljsClientScriptExecutor executor(ClientTool... tools) {
        ClientToolService svc = mock(ClientToolService.class);
        for (ClientTool t : tools) {
            when(svc.find(t.name())).thenReturn(t);
        }
        return new GraaljsClientScriptExecutor(engine, svc);
    }

    private static ClientScriptResult eval(GraaljsClientScriptExecutor exec, String code) {
        return exec.run(new ClientScriptRequest(
                "js", code, "test",
                new ClientExecutionContext("req-1", "sess-1", "proj-1"),
                Duration.ofSeconds(5)));
    }

    // ------------------------------------------------------------------
    // Happy path + sandbox
    // ------------------------------------------------------------------

    @Test
    void run_returnsPrimitive() {
        assertThat(eval(executor(), "1+2;").value()).isEqualTo(3L);
    }

    @Test
    void run_deniesJavaTypeAccess() {
        assertThatThrownBy(() -> eval(executor(), "Java.type('java.lang.System');"))
                .isInstanceOf(ClientScriptExecutionException.class);
    }

    @Test
    void run_aborts_onWallClockTimeout() {
        ClientToolService svc = mock(ClientToolService.class);
        GraaljsClientScriptExecutor exec = new GraaljsClientScriptExecutor(engine, svc);
        assertThatThrownBy(() -> exec.run(new ClientScriptRequest(
                "js", "while (true) {}", "test",
                new ClientExecutionContext("req-1", null, null),
                Duration.ofMillis(200))))
                .isInstanceOf(ClientScriptExecutionException.class)
                .satisfies(t -> assertThat(((ClientScriptExecutionException) t).errorClass())
                        .isIn(
                                ClientScriptExecutionException.ErrorClass.TIMEOUT,
                                ClientScriptExecutionException.ErrorClass.RESOURCE_EXHAUSTED,
                                ClientScriptExecutionException.ErrorClass.CANCELLED));
    }

    // ------------------------------------------------------------------
    // Host API: client.context, client.tools.call
    // ------------------------------------------------------------------

    @Test
    void clientContext_isReadable() {
        assertThat(eval(executor(), "client.context.requestId;").value()).isEqualTo("req-1");
        assertThat(eval(executor(), "client.context.sessionId;").value()).isEqualTo("sess-1");
        assertThat(eval(executor(), "client.context.projectId;").value()).isEqualTo("proj-1");
    }

    @Test
    void clientTools_call_dispatchesToClientToolService() {
        ClientTool echo = mock(ClientTool.class);
        when(echo.name()).thenReturn("client_echo");
        when(echo.invoke(any())).thenReturn(Map.of("ok", true));

        Object result = eval(executor(echo), "!!client.tools.call('client_echo', {}).ok;").value();
        assertThat(result).isEqualTo(true);
        verify(echo, times(1)).invoke(any());
    }

    @Test
    void clientTools_call_unknownTool_throwsInJs_andCanBeCaught() {
        String code =
                "try { client.tools.call('nope', {}); 'no-throw'; } catch (e) { 'caught'; }";
        assertThat(eval(executor(), code).value()).isEqualTo("caught");
    }

    // ------------------------------------------------------------------
    // §7.8 Foot-spezifisch
    // ------------------------------------------------------------------

    @Test
    void clientExecutor_doesNotExposeVanceNamespace() {
        assertThat(eval(executor(), "typeof vance;").value()).isEqualTo("undefined");
    }

    @Test
    void clientExecutor_rejectsServerToolNames() {
        // Server tool name like "web_search" is not registered locally, must throw.
        ClientToolService svc = mock(ClientToolService.class);
        when(svc.find("web_search")).thenReturn(null);
        GraaljsClientScriptExecutor exec = new GraaljsClientScriptExecutor(engine, svc);

        String code = "try { client.tools.call('web_search', {}); 'no-throw'; }"
                + " catch (e) { 'caught'; }";
        ClientScriptResult result = exec.run(new ClientScriptRequest(
                "js", code, "test",
                new ClientExecutionContext("req-1", null, null),
                Duration.ofSeconds(2)));
        assertThat(result.value()).isEqualTo("caught");
        verify(svc, never()).dispatch(any(), any(), any());
    }

    @Test
    void clientExecutor_doesNotPersistAcrossRequests() {
        GraaljsClientScriptExecutor exec = executor();
        eval(exec, "globalThis.clientTestFoo = 1;");
        Object second = eval(exec, "typeof globalThis.clientTestFoo;").value();
        assertThat(second).isEqualTo("undefined");
    }

    @Test
    void run_returnsMappedArray() {
        @SuppressWarnings("unchecked")
        List<Object> value = (List<Object>) eval(executor(), "[1, 2, 3];").value();
        assertThat(value).containsExactly(1L, 2L, 3L);
    }

    @Test
    void run_uncaughtJsError_isWrapped() {
        assertThatThrownBy(() -> eval(executor(), "throw new Error('boom');"))
                .isInstanceOf(ClientScriptExecutionException.class)
                .satisfies(t -> assertThat(((ClientScriptExecutionException) t).errorClass())
                        .isEqualTo(ClientScriptExecutionException.ErrorClass.GUEST_EXCEPTION));
    }
}
