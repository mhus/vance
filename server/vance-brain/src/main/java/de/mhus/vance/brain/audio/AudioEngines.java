package de.mhus.vance.brain.audio;

import de.mhus.vance.brain.tools.PodCapabilities;
import de.mhus.vance.brain.tools.ProjectCapabilities;
import de.mhus.vance.brain.tools.runner.RunnerWork;
import de.mhus.vance.toolpack.ToolCapabilities;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Picks where a project's audio is edited: ffmpeg on this pod when the pod has it, else on the
 * project's runner when that has it. The tools are only listed when one of the two applies
 * ({@link ProjectCapabilities}); reaching the end without an engine means the runner went away
 * between listing and call, and says so.
 */
@Component
@RequiredArgsConstructor
public class AudioEngines {

    private final FfmpegAudioEditor local;
    private final PodCapabilities pod;
    private final ProjectCapabilities project;
    private final RunnerWork runner;

    public AudioEngine engineFor(String tenantId, @Nullable String projectId) {
        if (pod.available().contains(ToolCapabilities.AUDIO_TOOLS)) {
            return local;
        }
        if (project.available(tenantId, projectId).contains(ToolCapabilities.AUDIO_TOOLS)) {
            return new RunnerAudioEngine(runner, tenantId, projectId);
        }
        throw new AudioManipulationException(
                AudioManipulationException.Reason.DISABLED,
                "Audio editing is not available here: this server has no ffmpeg and the project's runner does"
                        + " not offer it (or there is no runner).");
    }
}
