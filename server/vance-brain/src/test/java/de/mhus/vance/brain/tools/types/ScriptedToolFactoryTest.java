package de.mhus.vance.brain.tools.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.script.GraaljsScriptExecutor;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolSource;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.RecordingPermissionResolver;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code scripted}-type ToolFactory — spec:
 * {@code specification/server-tools.md} §3.1.
 *
 * <p>Validation tests use a stubbed executor; the end-to-end invoke
 * tests run against a real {@link GraaljsScriptExecutor} so that
 * binding-injection + last-expression-return are exercised.
 */
class ScriptedToolFactoryTest {

    private static Engine engine;
    private static ScriptExecutor realExecutor;

    private DocumentService documentService;
    private ScriptedToolFactory factory;

    @BeforeAll
    static void startEngine() {
        engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        realExecutor = new GraaljsScriptExecutor(engine);
    }

    @AfterAll
    static void stopEngine() {
        engine.close();
    }

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        factory = new ScriptedToolFactory(realExecutor, documentService);
    }

    // ──────────────────── validation ────────────────────

    @Test
    void create_missingSourceAndPath_rejected() {
        ServerToolDocument doc = serverTool("add", Map.of(
                "inputs", List.of(numberInput("a"))));

        assertThatThrownBy(() -> factory.create(doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source")
                .hasMessageContaining("scriptPath");
    }

    @Test
    void create_bothSourceAndPath_rejected() {
        ServerToolDocument doc = serverTool("add", Map.of(
                "inputs", List.of(numberInput("a")),
                "source", "a",
                "scriptPath", "scripts/foo.js"));

        assertThatThrownBy(() -> factory.create(doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source")
                .hasMessageContaining("scriptPath");
    }

    @Test
    void create_unknownEngine_rejected() {
        ServerToolDocument doc = serverTool("add", Map.of(
                "engine", "python",
                "inputs", List.of(numberInput("a")),
                "source", "a"));

        assertThatThrownBy(() -> factory.create(doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("python")
                .hasMessageContaining("javascript");
    }

    @Test
    void create_inputsMissing_rejected() {
        ServerToolDocument doc = serverTool("add", Map.of("source", "1"));

        assertThatThrownBy(() -> factory.create(doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputs");
    }

    @Test
    void create_inputWithoutName_rejected() {
        ServerToolDocument doc = serverTool("add", Map.of(
                "inputs", List.of(Map.of("type", "number")),
                "source", "1"));

        assertThatThrownBy(() -> factory.create(doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void create_inputWithUnknownType_rejected() {
        ServerToolDocument doc = serverTool("add", Map.of(
                "inputs", List.of(Map.of("name", "a", "type", "bigdecimal")),
                "source", "a"));

        assertThatThrownBy(() -> factory.create(doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bigdecimal");
    }

    @Test
    void create_duplicateInputNames_rejected() {
        ServerToolDocument doc = serverTool("add", Map.of(
                "inputs", List.of(numberInput("a"), numberInput("a")),
                "source", "a"));

        assertThatThrownBy(() -> factory.create(doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void create_reservedInputNameVance_rejected() {
        ServerToolDocument doc = serverTool("add", Map.of(
                "inputs", List.of(Map.of("name", "vance", "type", "string")),
                "source", "vance"));

        assertThatThrownBy(() -> factory.create(doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vance")
                .hasMessageContaining("reserved");
    }

    // ──────────────────── schema generation ────────────────────

    @Test
    void create_paramsSchemaReflectsInputs() {
        ServerToolDocument doc = serverTool("add", Map.of(
                "inputs", List.of(
                        Map.of("name", "a", "type", "number", "description", "first"),
                        Map.of("name", "b", "type", "number", "required", false)),
                "source", "a + (b || 0)"));

        Tool tool = single(factory.create(doc));
        Map<String, Object> schema = tool.paramsSchema();

        assertThat(schema).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKeys("a", "b");
        @SuppressWarnings("unchecked")
        Map<String, Object> aProp = (Map<String, Object>) properties.get("a");
        assertThat(aProp).containsEntry("type", "number");
        assertThat(aProp).containsEntry("description", "first");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).containsExactly("a"); // b had required=false
    }

    @Test
    void create_emptyInputs_schemaHasNoRequired() {
        ServerToolDocument doc = serverTool("now", Map.of(
                "inputs", List.of(),
                "source", "Date.now()"));

        Tool tool = single(factory.create(doc));
        Map<String, Object> schema = tool.paramsSchema();

        assertThat(schema).doesNotContainKey("required");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).isEmpty();
    }

    // ──────────────────── invoke (end-to-end) ────────────────────

    @Test
    void invoke_inlineSource_bindsInputsAndReturnsValue() {
        ServerToolDocument doc = serverTool("add", Map.of(
                "inputs", List.of(numberInput("a"), numberInput("b")),
                "source", "a + b"));
        Tool tool = single(factory.create(doc));

        Map<String, Object> result = tool.invoke(
                Map.of("a", 5, "b", 7),
                ctx(),
                tools());

        assertThat(result).containsEntry("value", 12L);
        assertThat(result).containsKey("durationMs");
    }

    @Test
    void invoke_missingRequiredInput_throwsToolException() {
        ServerToolDocument doc = serverTool("add", Map.of(
                "inputs", List.of(numberInput("a")),
                "source", "a"));
        Tool tool = single(factory.create(doc));

        assertThatThrownBy(() -> tool.invoke(Map.of(), ctx(), tools()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("required input 'a'");
    }

    @Test
    void invoke_optionalInputAbsent_passesNullBinding() {
        ServerToolDocument doc = serverTool("greet", Map.of(
                "inputs", List.of(Map.of(
                        "name", "name", "type", "string", "required", false)),
                "source", "name == null ? 'anon' : name"));
        Tool tool = single(factory.create(doc));

        Map<String, Object> result = tool.invoke(Map.of(), ctx(), tools());

        assertThat(result).containsEntry("value", "anon");
    }

    @Test
    void invoke_scriptRaisesError_returnsErrorMap() {
        ServerToolDocument doc = serverTool("boom", Map.of(
                "inputs", List.of(),
                "source", "throw new Error('kaboom')"));
        Tool tool = single(factory.create(doc));

        Map<String, Object> result = tool.invoke(Map.of(), ctx(), tools());

        assertThat(result).containsEntry("error", "GUEST_EXCEPTION");
        assertThat(result.get("message")).asString().contains("kaboom");
    }

    @Test
    void invoke_scriptPath_loadsViaCascadeAndExecutes() {
        ServerToolDocument doc = serverTool("doubler", Map.of(
                "inputs", List.of(numberInput("x")),
                "scriptPath", "scripts/doubler.js"));

        when(documentService.lookupCascade(eq("acme"), eq("proj-1"), eq("scripts/doubler.js")))
                .thenReturn(Optional.of(new LookupResult(
                        "scripts/doubler.js",
                        "x * 2",
                        LookupResult.Source.PROJECT,
                        null)));

        Tool tool = single(factory.create(doc));
        Map<String, Object> result = tool.invoke(Map.of("x", 21), ctx(), tools());

        assertThat(result).containsEntry("value", 42L);
    }

    @Test
    void invoke_scriptPathNotFound_throwsToolException() {
        ServerToolDocument doc = serverTool("missing", Map.of(
                "inputs", List.of(),
                "scriptPath", "scripts/missing.js"));

        when(documentService.lookupCascade(any(), any(), any()))
                .thenReturn(Optional.empty());

        Tool tool = single(factory.create(doc));

        assertThatThrownBy(() -> tool.invoke(Map.of(), ctx(), tools()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("scripts/missing.js");
    }

    // ──────────────────── helpers ────────────────────

    private static ServerToolDocument serverTool(String name, Map<String, Object> parameters) {
        return ServerToolDocument.builder()
                .name(name)
                .type(ScriptedToolFactory.TYPE_ID)
                .description("test tool")
                .parameters(new LinkedHashMap<>(parameters))
                .enabled(true)
                .primary(false)
                .labels(List.of())
                .build();
    }

    private static Map<String, Object> numberInput(String name) {
        return Map.of("name", name, "type", "number");
    }

    private static Tool single(Collection<Tool> tools) {
        assertThat(tools).hasSize(1);
        return tools.iterator().next();
    }

    private static ToolInvocationContext ctx() {
        return new ToolInvocationContext("acme", "proj-1", "sess-1", "proc-1", "alice");
    }

    private static ContextToolsApi tools() {
        ToolSource src = mock(ToolSource.class);
        when(src.sourceId()).thenReturn("test");
        when(src.tools(any())).thenReturn(List.<Tool>of());
        when(src.find(any(), any())).thenReturn(Optional.empty());
        ToolDispatcher dispatcher = new ToolDispatcher(
                List.of(src), new PermissionService(java.util.List.of(new RecordingPermissionResolver())),
                mock(de.mhus.vance.brain.agrajag.AgrajagChecker.class),
                mock(de.mhus.vance.shared.toolhealth.ToolHealthService.class),
                mock(de.mhus.vance.shared.team.TeamService.class));
        return new ContextToolsApi(dispatcher, ctx(), Set.of());
    }
}
