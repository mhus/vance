package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.runner.RunnerWork;
import de.mhus.vance.shared.workspace.WorkspaceMode;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolCapabilities;
import de.mhus.vance.toolpack.ToolException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What a project's tools may require: the pod's capabilities and its runner's. */
class ProjectCapabilitiesTest {

    private final RunnerWork runner = mock(RunnerWork.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-28T20:00:00Z"));
    private ProjectCapabilities capabilities;
    private PodCapabilities pod;

    @BeforeEach
    void setUp() {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setMode(WorkspaceMode.OFF);
        pod = new PodCapabilities(props, List.of());
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        capabilities = new ProjectCapabilities(pod, runner, clock);
    }

    private static Tool tool(String name, Set<String> required) {
        Tool tool = mock(Tool.class);
        when(tool.name()).thenReturn(name);
        when(tool.requiresCapabilities()).thenReturn(required);
        return tool;
    }

    @Test
    void aProjectWithoutARunnerHasTheCapabilitiesOfThePodAlone() {
        when(runner.runnerId("acme", "p1")).thenReturn(Optional.empty());

        assertThat(capabilities.available("acme", "p1")).isEqualTo(pod.available());
        assertThat(capabilities.supports(tool("runner_file_read", Set.of(ToolCapabilities.RUNNER)), "acme", "p1"))
                .isFalse();
        assertThat(capabilities.supports(tool("doc_read", Set.of()), "acme", "p1"))
                .isTrue();
    }

    @Test
    void aRunnerBringsTheRunnerCapability_andWhatItReportsOfItself() {
        when(runner.runnerId("acme", "p1")).thenReturn(Optional.of("runner-acme"));
        when(runner.reportedCapabilities("acme", "p1"))
                .thenReturn(Set.of("rootdir", "file", ToolCapabilities.AUDIO_TOOLS, "something-else"));

        Set<String> available = capabilities.available("acme", "p1");

        assertThat(available).contains(ToolCapabilities.RUNNER, ToolCapabilities.AUDIO_TOOLS);
        // Descriptive names and anything a runner may not provide never become a tool capability.
        assertThat(available).doesNotContain("rootdir", "file", "something-else", ToolCapabilities.WORKSPACE);
        assertThat(capabilities.supports(tool("audio_trim", Set.of(ToolCapabilities.AUDIO_TOOLS)), "acme", "p1"))
                .isTrue();
    }

    @Test
    void aRunnerThatCannotDoAudio_doesNotOfferAudioTools() {
        when(runner.runnerId("acme", "p1")).thenReturn(Optional.of("runner-acme"));
        when(runner.reportedCapabilities("acme", "p1")).thenReturn(Set.of("rootdir", "file", "exec"));

        assertThat(capabilities.supports(tool("audio_trim", Set.of(ToolCapabilities.AUDIO_TOOLS)), "acme", "p1"))
                .isFalse();
        assertThat(capabilities.supports(tool("runner_exec_run", Set.of(ToolCapabilities.RUNNER)), "acme", "p1"))
                .isTrue();
    }

    @Test
    void whatTheRunnerReportsIsAskedOnceAMinute() {
        when(runner.runnerId("acme", "p1")).thenReturn(Optional.of("runner-acme"));
        when(runner.reportedCapabilities("acme", "p1")).thenReturn(Set.of(ToolCapabilities.AUDIO_TOOLS));

        capabilities.available("acme", "p1");
        capabilities.available("acme", "p1");
        now.set(now.get().plus(Duration.ofSeconds(59)));
        capabilities.available("acme", "p1");
        verify(runner, times(1)).reportedCapabilities("acme", "p1");

        now.set(now.get().plus(Duration.ofSeconds(2)));
        capabilities.available("acme", "p1");
        verify(runner, times(2)).reportedCapabilities("acme", "p1");
    }

    @Test
    void anUnreachableRunnerKeepsTheRunnerToolsListed_butAddsNothing_andIsAskedAgainSoon() {
        when(runner.runnerId("acme", "p1")).thenReturn(Optional.of("runner-acme"));
        when(runner.reportedCapabilities(any(), any())).thenThrow(new ToolException("not reachable"));

        assertThat(capabilities.available("acme", "p1"))
                .contains(ToolCapabilities.RUNNER)
                .doesNotContain(ToolCapabilities.AUDIO_TOOLS);

        // It recovers: the next look after the short pause sees what it reports.
        org.mockito.Mockito.doReturn(Set.of(ToolCapabilities.AUDIO_TOOLS))
                .when(runner)
                .reportedCapabilities(any(), any());
        now.set(now.get().plus(Duration.ofSeconds(16)));
        assertThat(capabilities.available("acme", "p1")).contains(ToolCapabilities.AUDIO_TOOLS);
    }

    @Test
    void onlyToolsThatNeedWhatARunnerCanBringDependOnOne() {
        assertThat(capabilities.dependsOnRunner(tool("runner_file_read", Set.of(ToolCapabilities.RUNNER))))
                .isTrue();
        assertThat(capabilities.dependsOnRunner(tool("audio_trim", Set.of(ToolCapabilities.AUDIO_TOOLS))))
                .isTrue();
        // A workspace tool needs the pod's own workspace; no runner changes that.
        assertThat(capabilities.dependsOnRunner(tool("work_file_read", Set.of(ToolCapabilities.WORKSPACE))))
                .isFalse();
        assertThat(capabilities.dependsOnRunner(tool("doc_read", Set.of()))).isFalse();
        assertThat(capabilities.dependsOnRunner(
                        tool("mixed", Set.of(ToolCapabilities.RUNNER, ToolCapabilities.WORKSPACE))))
                .isFalse();
    }
}
