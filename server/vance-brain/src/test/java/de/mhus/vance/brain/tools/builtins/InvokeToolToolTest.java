package de.mhus.vance.brain.tools.builtins;

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
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the {@code invoke_tool} sandbox/role-gate bypass
 * (code-review BLOCKER B4). {@code invoke_tool} must route through the
 * engine's bound {@link ContextToolsApi} so the target name is subjected
 * to the same allow-set gate as a direct LLM call — it must not be able
 * to reach a tool the engine is not allowed to invoke.
 */
class InvokeToolToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj", "sess-1", "p-1", "alice");

    private ToolDispatcher dispatcher;
    private Tool gatedTool;

    @BeforeEach
    void setUp() {
        // A role-gated tool the engine is NOT allowed to use (stands in for
        // cross_process_create / any requiresEngineRoles tool).
        gatedTool = mock(Tool.class);
        when(gatedTool.name()).thenReturn("gated.tool");
        when(gatedTool.requiresEngineRoles()).thenReturn(Set.of("trillian-user"));
        when(gatedTool.invoke(any(), any())).thenReturn(Map.of("ran", true));
        when(gatedTool.invoke(any(), any(), any())).thenReturn(Map.of("ran", true));

        // A regular tool that IS in the engine's allow-set.
        Tool allowedTarget = mock(Tool.class);
        when(allowedTarget.name()).thenReturn("target.tool");
        when(allowedTarget.invoke(any(), any())).thenReturn(Map.of("ran", true));
        when(allowedTarget.invoke(any(), any(), any())).thenReturn(Map.of("ran", true));

        Tool invokeTool = new InvokeToolTool();

        ToolSource src = mock(ToolSource.class);
        when(src.sourceId()).thenReturn("test");
        when(src.find(eq("invoke_tool"), any())).thenReturn(Optional.of(invokeTool));
        when(src.find(eq("gated.tool"), any())).thenReturn(Optional.of(gatedTool));
        when(src.find(eq("target.tool"), any())).thenReturn(Optional.of(allowedTarget));
        when(src.tools(any())).thenReturn(List.of(invokeTool, gatedTool, allowedTarget));

        dispatcher = new ToolDispatcher(List.of(src),
                new PermissionService(new RecordingPermissionResolver()),
                mock(de.mhus.vance.brain.agrajag.AgrajagChecker.class),
                mock(de.mhus.vance.shared.toolhealth.ToolHealthService.class));
    }

    @Test
    void invokeTool_cannotReachToolOutsideEngineAllowSet() {
        // Allow-set contains invoke_tool but NOT gated.tool.
        ContextToolsApi api = new ContextToolsApi(dispatcher, CTX, Set.of("invoke_tool"));

        assertThatThrownBy(() -> api.invoke("invoke_tool",
                Map.of("name", "gated.tool", "params", Map.of())))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not available to this engine");

        // The gated tool must never have been dispatched.
        org.mockito.Mockito.verify(gatedTool, org.mockito.Mockito.never())
                .invoke(any(), any());
        org.mockito.Mockito.verify(gatedTool, org.mockito.Mockito.never())
                .invoke(any(), any(), any());
    }

    @Test
    void invokeTool_dispatchesToolInsideEngineAllowSet() {
        ContextToolsApi api =
                new ContextToolsApi(dispatcher, CTX, Set.of("invoke_tool", "target.tool"));

        Map<String, Object> result = api.invoke("invoke_tool",
                Map.of("name", "target.tool", "params", Map.of()));

        assertThat(result).containsEntry("ran", true);
    }

    @Test
    void invokeTool_withoutBus_failsClosed() {
        assertThatThrownBy(() -> new InvokeToolTool().invoke(
                Map.of("name", "gated.tool"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("requires an engine tool surface");
    }

    @Test
    void invokeTool_cannotInvokeItself() {
        ContextToolsApi api = new ContextToolsApi(dispatcher, CTX, Set.of("invoke_tool"));

        assertThatThrownBy(() -> api.invoke("invoke_tool",
                Map.of("name", "invoke_tool", "params", Map.of())))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("cannot invoke itself");
    }
}
