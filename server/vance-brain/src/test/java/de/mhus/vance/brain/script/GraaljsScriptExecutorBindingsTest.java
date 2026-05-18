package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolSource;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.RecordingPermissionResolver;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ScriptRequest#bindings()} are injected as
 * top-level variables into the GraalJS context — the mechanism that
 * lets {@code scripted}-type server tools pass typed inputs to the
 * script body. Companion to {@link ScriptExecutorTest}.
 */
class GraaljsScriptExecutorBindingsTest {

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

    private static ContextToolsApi tools() {
        ToolSource src = mock(ToolSource.class);
        when(src.sourceId()).thenReturn("test");
        when(src.tools(any())).thenReturn(List.<Tool>of());
        when(src.find(any(), any())).thenReturn(Optional.empty());
        ToolDispatcher dispatcher = new ToolDispatcher(
                List.of(src), new PermissionService(new RecordingPermissionResolver()));
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj-1", "sess-1", "proc-1", "alice");
        return new ContextToolsApi(dispatcher, ctx, Set.of());
    }

    @Test
    void run_bindingsAreVisibleAsTopLevelVariables() {
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("a", 5L);
        bindings.put("b", 7L);
        ScriptRequest req = new ScriptRequest(
                "js", "a + b", "test", tools(), Duration.ofSeconds(5), bindings);

        ScriptResult result = executor.run(req);

        assertThat(result.value()).isEqualTo(12L);
    }

    @Test
    void run_emptyBindings_runsCleanly() {
        ScriptRequest req = new ScriptRequest(
                "js", "1 + 2", "test", tools(), Duration.ofSeconds(5), Map.of());

        assertThat(executor.run(req).value()).isEqualTo(3L);
    }

    @Test
    void run_objectBinding_isAccessibleAsJsObject() {
        Map<String, Object> shipped = new LinkedHashMap<>();
        shipped.put("name", "alice");
        shipped.put("age", 30);
        Map<String, Object> bindings = Map.of("user", shipped);

        ScriptRequest req = new ScriptRequest(
                "js", "user.name + ':' + user.age", "test", tools(),
                Duration.ofSeconds(5), bindings);

        assertThat(executor.run(req).value()).isEqualTo("alice:30");
    }

    @Test
    void run_nullValueBinding_isPassedAsNull() {
        // HashMap allows null values; Map.of does not. Tests that the
        // executor doesn't filter null bindings — a script that asks
        // for an optional input should see undefined / null.
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("maybe", null);
        ScriptRequest req = new ScriptRequest(
                "js", "maybe == null", "test", tools(),
                Duration.ofSeconds(5), bindings);

        assertThat(executor.run(req).value()).isEqualTo(true);
    }

    @Test
    void scriptRequest_reservedBindingName_rejected() {
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("vance", "hijack");
        assertThatThrownBy(() -> new ScriptRequest(
                        "js", "vance", "test", tools(), Duration.ofSeconds(5), bindings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vance");
    }

    @Test
    void scriptRequest_nullBindings_rejected() {
        assertThatThrownBy(() -> new ScriptRequest(
                        "js", "1", "test", tools(), Duration.ofSeconds(5),
                        (Map<String, Object>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bindings");
    }

    @Test
    void scriptRequest_legacyConstructor_stillWorks() {
        // 5-arg shape used by existing JavaScriptTool / runFile call
        // sites must still compile and run with no bindings.
        ScriptRequest req = new ScriptRequest(
                "js", "21 * 2", "test", tools(), Duration.ofSeconds(5));

        assertThat(req.bindings()).isEmpty();
        assertThat(req.recipeName()).isNull();
        assertThat(executor.run(req).value()).isEqualTo(42L);
    }

    @Test
    void run_recipeName_visibleAsVanceContextRecipe() {
        // 7-arg form: recipe name propagates through to the script
        // as vance.context.recipe.
        ScriptRequest req = new ScriptRequest(
                "js", "vance.context.recipe", "test",
                tools(), Duration.ofSeconds(5), Map.of(),
                "script-developer");

        assertThat(executor.run(req).value()).isEqualTo("script-developer");
    }

    @Test
    void run_recipeName_omitted_returnsNull() {
        // 6-arg legacy form (no recipe) — script sees null.
        ScriptRequest req = new ScriptRequest(
                "js", "vance.context.recipe", "test",
                tools(), Duration.ofSeconds(5), Map.of());

        assertThat(executor.run(req).value()).isNull();
    }

    @Test
    void run_vanceContextFields_allAccessible() {
        // Quick smoke check the full ScriptContextView is reachable:
        // tenantId/projectId/sessionId/processId/userId/recipe.
        String code = "JSON.stringify({"
                + " tenantId: vance.context.tenantId,"
                + " projectId: vance.context.projectId,"
                + " sessionId: vance.context.sessionId,"
                + " processId: vance.context.processId,"
                + " userId: vance.context.userId,"
                + " recipe: vance.context.recipe"
                + "})";
        ScriptRequest req = new ScriptRequest(
                "js", code, "test",
                tools(), Duration.ofSeconds(5), Map.of(), "my-recipe");

        Object value = executor.run(req).value();
        assertThat(value).asString()
                .contains("\"tenantId\":\"acme\"")
                .contains("\"projectId\":\"proj-1\"")
                .contains("\"sessionId\":\"sess-1\"")
                .contains("\"processId\":\"proc-1\"")
                .contains("\"userId\":\"alice\"")
                .contains("\"recipe\":\"my-recipe\"");
    }
}
