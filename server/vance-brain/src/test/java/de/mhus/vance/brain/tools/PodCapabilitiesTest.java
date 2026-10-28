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
        assertThat(new PodCapabilities(properties(WorkspaceMode.LOCAL), List.of()).available())
                .containsExactly(ToolCapabilities.WORKSPACE);
    }

    @Test
    void offMode_offersNothing() {
        assertThat(new PodCapabilities(properties(WorkspaceMode.OFF), List.of()).available())
                .isEmpty();
    }

    @Test
    void contributors_addTheirCapabilities() {
        PodCapabilityContributor ffmpeg = () -> Set.of(ToolCapabilities.AUDIO_TOOLS);
        PodCapabilityContributor none = Set::of;

        assertThat(new PodCapabilities(properties(WorkspaceMode.OFF), List.of(ffmpeg, none)).available())
                .containsExactly(ToolCapabilities.AUDIO_TOOLS);
    }

    @Test
    void audioTool_withoutAudioCapability_isWithheld() {
        Tool trim = tool("audio_trim", Set.of(ToolCapabilities.AUDIO_TOOLS));

        BuiltInToolSource without = new BuiltInToolSource(
                List.of(trim),
                mock(ServerToolService.class),
                new PodCapabilities(properties(WorkspaceMode.OFF), List.of()),
                mock(ProjectCapabilities.class));
        BuiltInToolSource with = new BuiltInToolSource(
                List.of(trim),
                mock(ServerToolService.class),
                new PodCapabilities(properties(WorkspaceMode.OFF), List.of(() -> Set.of(ToolCapabilities.AUDIO_TOOLS))),
                mock(ProjectCapabilities.class));

        assertThat(without.find("audio_trim")).isEmpty();
        assertThat(with.find("audio_trim")).contains(trim);
    }

    @Test
    void builtInSource_offMode_withholdsWorkspaceToolsOnly() {
        Tool plain = tool("doc_read", Set.of());
        Tool workspace = tool("work_file_read", Set.of(ToolCapabilities.WORKSPACE));

        BuiltInToolSource source = new BuiltInToolSource(
                List.of(plain, workspace),
                mock(ServerToolService.class),
                new PodCapabilities(properties(WorkspaceMode.OFF), List.of()),
                mock(ProjectCapabilities.class));

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
                new PodCapabilities(properties(WorkspaceMode.LOCAL), List.of()),
                mock(ProjectCapabilities.class));

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

    @Test
    void builtInSource_offersARunnerDependentTool_onlyToProjectsWhoseRunnerSupportsIt() {
        Tool trim = tool("audio_trim", Set.of(ToolCapabilities.AUDIO_TOOLS));
        Tool plain = tool("doc_read", Set.of());
        ProjectCapabilities project = mock(ProjectCapabilities.class);
        org.mockito.Mockito.when(project.dependsOnRunner(trim)).thenReturn(true);
        org.mockito.Mockito.when(project.supports(trim, "acme", "with-runner")).thenReturn(true);
        org.mockito.Mockito.when(project.supports(trim, "acme", "without-runner"))
                .thenReturn(false);

        BuiltInToolSource source = new BuiltInToolSource(
                List.of(plain, trim),
                mock(ServerToolService.class),
                new PodCapabilities(properties(WorkspaceMode.OFF), List.of()),
                project);

        assertThat(source.list()).containsExactly(plain); // the pod view is unchanged
        assertThat(source.list("acme", "with-runner")).containsExactly(plain, trim);
        assertThat(source.list("acme", "without-runner")).containsExactly(plain);
        assertThat(source.find("audio_trim", "acme", "with-runner")).contains(trim);
        assertThat(source.find("audio_trim", "acme", "without-runner")).isEmpty();
        assertThat(source.find("doc_read", "acme", "without-runner")).contains(plain);
    }
}
