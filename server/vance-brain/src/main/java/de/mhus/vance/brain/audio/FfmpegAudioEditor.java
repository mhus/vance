package de.mhus.vance.brain.audio;

import de.mhus.vance.brain.tools.PodCapabilityContributor;
import de.mhus.vance.exec.FfmpegEngine;
import de.mhus.vance.exec.MechException;
import de.mhus.vance.exec.ProcessLauncher;
import de.mhus.vance.exec.ScratchArea;
import de.mhus.vance.toolpack.ToolCapabilities;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Audio editing with the ffmpeg on this pod: the {@link AudioEngine} that runs where the brain
 * runs. The work is {@link FfmpegEngine}'s — the same code the runner uses; this class adds
 * what belongs to the pod: the sealed child environment (the launcher), the capability it offers
 * and the mapping of failures to {@link AudioManipulationException}.
 *
 * <p>Output format is always one of {@code mp3} (libmp3lame, 192 kbit/s) or {@code wav}
 * (pcm_s16le) — the two containers every consumer of these documents can play.
 */
@Component
@Slf4j
public class FfmpegAudioEditor implements AudioEngine, PodCapabilityContributor {

    private final FfmpegEngine engine;

    public FfmpegAudioEditor(
            @Value("${vance.audio.ffmpeg-bin:ffmpeg}") String ffmpegBin,
            @Value("${vance.audio.ffprobe-bin:ffprobe}") String ffprobeBin,
            @Value("${vance.audio.edit-timeout-seconds:600}") long timeoutSeconds,
            ProcessLauncher launcher,
            ScratchArea scratchArea) {
        this.engine = new FfmpegEngine(ffmpegBin, ffprobeBin, timeoutSeconds, launcher, scratchArea);
    }

    /**
     * {@link ToolCapabilities#AUDIO_TOOLS} when both ffmpeg and ffprobe run here — the cloud
     * image ships without them, and then the audio edit tools come from a project's runner or
     * stay out of the inventory instead of failing on every call.
     */
    @Override
    public Set<String> capabilities() {
        return engine.available() ? Set.of(ToolCapabilities.AUDIO_TOOLS) : Set.of();
    }

    @Override
    public ProbeData probe(byte[] audio) {
        try {
            FfmpegEngine.ProbeData data = engine.probe(audio);
            return new ProbeData(
                    data.durationSeconds(), data.format(), data.codec(), data.channels(), data.sampleRate());
        } catch (MechException e) {
            throw map(e);
        }
    }

    @Override
    public byte[] trim(
            byte[] audio,
            double startSeconds,
            @Nullable Double endSeconds,
            double fadeInSeconds,
            double fadeOutSeconds,
            String outputFormat) {
        try {
            return engine.trim(audio, startSeconds, endSeconds, fadeInSeconds, fadeOutSeconds, outputFormat);
        } catch (MechException e) {
            throw map(e);
        }
    }

    @Override
    public byte[] mix(
            byte[] base,
            byte[] overlay,
            double baseGain,
            double overlayGain,
            double overlayAtSeconds,
            boolean duckOverlay,
            double fadeOutSeconds,
            String outputFormat) {
        try {
            return engine.mix(
                    base, overlay, baseGain, overlayGain, overlayAtSeconds, duckOverlay, fadeOutSeconds, outputFormat);
        } catch (MechException e) {
            throw map(e);
        }
    }

    @Override
    public byte[] concat(List<byte[]> clips, double crossfadeSeconds, String outputFormat) {
        try {
            return engine.concat(clips, crossfadeSeconds, outputFormat);
        } catch (MechException e) {
            throw map(e);
        }
    }

    @Override
    public byte[] convert(
            byte[] audio,
            @Nullable Integer sampleRate,
            @Nullable Integer channels,
            @Nullable Integer bitrateKbps,
            boolean normalize,
            String outputFormat) {
        try {
            return engine.convert(audio, sampleRate, channels, bitrateKbps, normalize, outputFormat);
        } catch (MechException e) {
            throw map(e);
        }
    }

    /** The same vocabulary on both sides: a {@link MechException.Reason} is an {@link AudioManipulationException.Reason}. */
    static AudioManipulationException map(MechException e) {
        AudioManipulationException.Reason reason =
                switch (e.reason()) {
                    case PARAMETER_INVALID -> AudioManipulationException.Reason.PARAMETER_INVALID;
                    case FORMAT_UNSUPPORTED -> AudioManipulationException.Reason.FORMAT_UNSUPPORTED;
                    case PROCESSING_ERROR -> AudioManipulationException.Reason.PROCESSING_ERROR;
                    case UNAVAILABLE -> AudioManipulationException.Reason.DISABLED;
                };
        return e.getCause() == null
                ? new AudioManipulationException(reason, e.getMessage())
                : new AudioManipulationException(reason, e.getMessage(), e.getCause());
    }
}
