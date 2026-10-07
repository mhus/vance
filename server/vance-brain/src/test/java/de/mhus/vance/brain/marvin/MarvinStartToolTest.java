package de.mhus.vance.brain.marvin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link MarvinStartTool}: the goal contract (decision F3.1,
 * Folge-Lauf-Regel — the tree reads ONLY the goal), the live-tree gate
 * (F2), and the engine-param overrides persisted for the new run.
 */
class MarvinStartToolTest {

    private final ThinkProcessService processes = mock(ThinkProcessService.class);
    private final MarvinEngine engine = mock(MarvinEngine.class);
    private final MarvinStartTool tool = new MarvinStartTool(processes, engine);

    private ThinkProcessDocument process;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setUp() {
        process = new ThinkProcessDocument();
        process.setId("marvin-1");
        process.setTenantId("t");
        process.setProjectId("p");
        process.setSessionId("s");
        ctx = mock(ToolInvocationContext.class);
        when(ctx.processId()).thenReturn("marvin-1");
        when(processes.findById("marvin-1")).thenReturn(Optional.of(process));
    }

    @Test
    void aMissingGoalRefusesTheStart() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("goal");

        verify(engine, never()).startTree(any(), any());
    }

    @Test
    void aLiveTreeRefusesTheStart() {
        when(engine.treeIsLive(process)).thenReturn(true);

        assertThatThrownBy(() -> tool.invoke(Map.of("goal", "again"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("marvin_stop");

        verify(engine, never()).startTree(any(), any());
    }

    @Test
    void aTerminalTreeAllowsTheRestart() throws Exception {
        when(engine.treeIsLive(process)).thenReturn(false);

        Map<String, Object> out = tool.invoke(Map.of("goal", "think about X, building on: <findings>"), ctx);

        assertThat(out).containsEntry("started", true).containsEntry("goal", "think about X, building on: <findings>");
        verify(engine).startTree(any(), eq("think about X, building on: <findings>"));
    }

    @Test
    void overridesArePersistedForTheNewRun() throws Exception {
        when(engine.treeIsLive(process)).thenReturn(false);
        process.setEngineParams(new LinkedHashMap<>(Map.of(MarvinEngine.PARAM_CHAT_IDENTITY, true)));

        tool.invoke(
                Map.of(
                        "goal",
                        "the goal",
                        "availableRecipes",
                        List.of("web-research"),
                        "maxTreeNodes",
                        50,
                        "maxTreeDepth",
                        3),
                ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> persisted = ArgumentCaptor.forClass(Map.class);
        verify(processes).replaceEngineParams(eq("marvin-1"), persisted.capture());
        assertThat(persisted.getValue())
                .containsEntry("availableRecipes", List.of("web-research"))
                .containsEntry("maxTreeNodes", 50)
                .containsEntry("maxTreeDepth", 3)
                // Marvin's own control keys are never overwritten by the overrides.
                .containsEntry(MarvinEngine.PARAM_CHAT_IDENTITY, true);
    }

    @Test
    void withoutOverridesNothingIsPersisted() throws Exception {
        when(engine.treeIsLive(process)).thenReturn(false);
        process.setEngineParams(new LinkedHashMap<>());

        tool.invoke(Map.of("goal", "the goal"), ctx);

        verify(processes, never()).replaceEngineParams(any(), any());
        verify(engine).startTree(any(), eq("the goal"));
    }
}
