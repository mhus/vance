package de.mhus.vance.brain.audio;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where audio editing runs: ffmpeg on this pod ({@link FfmpegAudioEditor}) or on the
 * project's runner ({@link RunnerAudioEngine}). Operates on raw bytes — document IO, limits
 * and validation live in {@link AudioManipulationService}, which asks {@link AudioEngines}
 * for the engine of the project it works for.
 *
 * <p>Failures are {@link AudioManipulationException}s, whichever side ran the program.
 */
public interface AudioEngine {

    /** What ffprobe reports about one audio input. */
    record ProbeData(
            @Nullable Double durationSeconds,
            @Nullable String format,
            @Nullable String codec,
            @Nullable Integer channels,
            @Nullable Integer sampleRate) {}

    ProbeData probe(byte[] audio);

    byte[] trim(
            byte[] audio,
            double startSeconds,
            @Nullable Double endSeconds,
            double fadeInSeconds,
            double fadeOutSeconds,
            String outputFormat);

    byte[] mix(
            byte[] base,
            byte[] overlay,
            double baseGain,
            double overlayGain,
            double overlayAtSeconds,
            boolean duckOverlay,
            double fadeOutSeconds,
            String outputFormat);

    byte[] concat(List<byte[]> clips, double crossfadeSeconds, String outputFormat);

    byte[] convert(
            byte[] audio,
            @Nullable Integer sampleRate,
            @Nullable Integer channels,
            @Nullable Integer bitrateKbps,
            boolean normalize,
            String outputFormat);
}
