package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.shared.workspace.WorkspaceMode;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolCapabilities;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PodCapabilitiesTest {

    @Test
    void localMode_offersWorkspace() {
        assertThat(new PodCapabilities(properties(WorkspaceMode.LOCAL)).available())
                .containsExactly(ToolCapabilities.WORKSPACE);
    }

    @Test
    void offMode_offersNothing() {
        assertThat(new PodCapabilities(properties(WorkspaceMode.OFF)).available())
                .isEmpty();
    }

    @Test
    void builtInSource_offMode_withholdsWorkspaceToolsOnly() {
        Tool plain = tool("doc_read", Set.of());
        Tool workspace = tool("work_file_read", Set.of(ToolCapabilities.WORKSPACE));

        BuiltInToolSource source = new BuiltInToolSource(
                List.of(plain, workspace),
                mock(ServerToolService.class),
                new PodCapabilities(properties(WorkspaceMode.OFF)));

        assertThat(source.list()).containsExactly(plain);
        assertThat(source.find("work_file_read")).isEmpty();
        assertThat(source.find("doc_read")).contains(plain);
    }

    @Test
    void builtInSource_localMode_keepsEveryTool() {
        Tool workspace = tool("work_file_read", Set.of(ToolCapabilities.WORKSPACE));

        BuiltInToolSource source = new BuiltInToolSource(
                List.of(workspace),
                mock(ServerToolService.class),
                new PodCapabilities(properties(WorkspaceMode.LOCAL)));

        assertThat(source.find("work_file_read")).contains(workspace);
    }

    private static WorkspaceProperties properties(WorkspaceMode mode) {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setMode(mode);
        return props;
    }

    private static Tool tool(String name, Set<String> capabilities) {
        Tool tool = mock(Tool.class);
        when(tool.name()).thenReturn(name);
        when(tool.requiresCapabilities()).thenReturn(capabilities);
        return tool;
    }
}
