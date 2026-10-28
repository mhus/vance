package de.mhus.vance.brain.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.PodCapabilities;
import de.mhus.vance.brain.tools.ProjectCapabilities;
import de.mhus.vance.brain.tools.runner.RunnerWork;
import de.mhus.vance.toolpack.ToolCapabilities;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Where a project's audio is edited: this pod first, then its runner, else nowhere — with a reason. */
class AudioEnginesTest {

    private final FfmpegAudioEditor local = mock(FfmpegAudioEditor.class);
    private final PodCapabilities pod = mock(PodCapabilities.class);
    private final ProjectCapabilities project = mock(ProjectCapabilities.class);
    private final RunnerWork runner = mock(RunnerWork.class);
    private final AudioEngines engines = new AudioEngines(local, pod, project, runner);

    @Test
    void aPodWithFfmpegEditsLocally_whateverTheProjectsRunner() {
        when(pod.available()).thenReturn(Set.of(ToolCapabilities.AUDIO_TOOLS));

        assertThat(engines.engineFor("acme", "p1")).isSameAs(local);
    }

    @Test
    void aPodWithoutFfmpegUsesTheRunnerOfAProjectThatHasOne() {
        when(pod.available()).thenReturn(Set.of());
        when(project.available("acme", "p1")).thenReturn(Set.of(ToolCapabilities.RUNNER, ToolCapabilities.AUDIO_TOOLS));

        assertThat(engines.engineFor("acme", "p1")).isInstanceOf(RunnerAudioEngine.class);
    }

    @Test
    void withoutBothTheCallSaysWhyNothingCanEdit() {
        when(pod.available()).thenReturn(Set.of());
        when(project.available("acme", "p1"))
                .thenReturn(Set.of(ToolCapabilities.RUNNER)); // a runner, but no ffmpeg on it

        assertThatThrownBy(() -> engines.engineFor("acme", "p1"))
                .isInstanceOfSatisfying(AudioManipulationException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(AudioManipulationException.Reason.DISABLED);
                    assertThat(e.getMessage()).contains("no ffmpeg").contains("runner");
                });
    }
}
