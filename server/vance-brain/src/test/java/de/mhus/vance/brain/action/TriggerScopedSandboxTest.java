package de.mhus.vance.brain.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.script.VanceScriptApi;
import de.mhus.vance.brain.script.VanceScriptApi.ScriptHostException;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * End-to-end smoke test for the {@link ScopeLevel#TRIGGER_SCOPED}
 * sandbox: a {@link VanceScriptApi.ScriptToolsApi} configured with a
 * non-empty {@code deniedToolNames} set refuses to dispatch those
 * tools regardless of whether they would have succeeded downstream.
 *
 * <p>Process-scoped {@code VanceScriptApi} (empty deny-set) keeps
 * routing through the dispatcher as before.
 */
class TriggerScopedSandboxTest {

    private final ToolInvocationContext scope =
            new ToolInvocationContext("t1", "p1", null, null, null);

    @Test
    void trigger_scoped_blocks_spawn_tool_calls_with_clear_message() {
        ToolDispatcher dispatcher = Mockito.mock(ToolDispatcher.class);
        ContextToolsApi tools = new ContextToolsApi(dispatcher, scope);
        VanceScriptApi api = new VanceScriptApi(tools, null, Set.of("process_create"));

        assertThatThrownBy(() -> api.tools.call("process_create", Map.of()))
                .isInstanceOf(ScriptHostException.class)
                .hasMessageContaining("process_create")
                .hasMessageContaining("not allowed in trigger-scoped script")
                .hasMessageContaining("wrap in a workflow");
        Mockito.verifyNoInteractions(dispatcher);
    }

    @Test
    void trigger_scoped_allows_non_denied_tool_through_dispatcher() {
        ToolDispatcher dispatcher = Mockito.mock(ToolDispatcher.class);
        Mockito.when(dispatcher.invoke(Mockito.eq("doc_read"), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Map.of("content", "hello"));
        ContextToolsApi tools = new ContextToolsApi(dispatcher, scope, Set.of("doc_read"));
        VanceScriptApi api = new VanceScriptApi(tools, null, Set.of("process_create"));

        Map<String, Object> result = api.tools.call("doc_read", Map.of("path", "x.md"));

        assertThat(result).containsEntry("content", "hello");
    }

    @Test
    void process_scoped_with_empty_deny_set_passes_through() {
        ToolDispatcher dispatcher = Mockito.mock(ToolDispatcher.class);
        Mockito.when(dispatcher.invoke(Mockito.eq("process_create"), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Map.of("processId", "p-1"));
        ContextToolsApi tools = new ContextToolsApi(dispatcher, scope, Set.of("process_create"));
        VanceScriptApi api = new VanceScriptApi(tools, null, Set.of());

        Map<String, Object> result = api.tools.call("process_create", Map.of("recipe", "analyze"));

        assertThat(result).containsEntry("processId", "p-1");
    }

    @Test
    void dispatcher_tool_exception_is_wrapped_as_ScriptHostException() {
        ToolDispatcher dispatcher = Mockito.mock(ToolDispatcher.class);
        Mockito.when(dispatcher.invoke(Mockito.eq("doc_read"), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new ToolException("not found"));
        ContextToolsApi tools = new ContextToolsApi(dispatcher, scope, Set.of("doc_read"));
        VanceScriptApi api = new VanceScriptApi(tools, null, Set.of());

        assertThatThrownBy(() -> api.tools.call("doc_read", Map.of()))
                .isInstanceOf(ScriptHostException.class)
                .hasMessageContaining("not found");
    }
}
