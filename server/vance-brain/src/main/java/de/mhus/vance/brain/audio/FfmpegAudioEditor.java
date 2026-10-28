package de.mhus.vance.brain.audio;

import de.mhus.vance.brain.tools.PodCapabilityContributor;
import de.mhus.vance.exec.ProcessLauncher;
import de.mhus.vance.exec.ProcessSpec;
import de.mhus.vance.exec.ProgramProbe;
import de.mhus.vance.exec.ScratchArea;
import de.mhus.vance.exec.ScratchArea.Scratch;
import de.mhus.vance.toolpack.ToolCapabilities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ffmpeg/ffprobe engine wrapper for audio editing: the local,
 * deterministic counterpart to the model providers under
 * {@code ai.audio}. Operates on raw bytes — document IO, limits and
 * validation live in {@link AudioManipulationService}; this class only
 * builds argument lists, runs the binaries and parses their output.
 *
 * <p>Every invocation runs with an explicit argument list (never a
 * shell string), a wall-clock timeout and a scratch directory of its
 * own ({@link ScratchArea}) that holds input and output and is removed
 * afterwards; an isolated child sees only that directory. ffmpeg writes its diagnostics to
 * stderr only on failure ({@code -loglevel error}); the last lines of
 * that output become the {@code PROCESSING_ERROR} message.
 *
 * <p>Output format is always one of {@code mp3} (libmp3lame,
 * 192 kbit/s) or {@code wav} (pcm_s16le) — the two containers every
 * consumer of these documents can play.
 */
@Component
@Slf4j
public class FfmpegAudioEditor implements PodCapabilityContributor {

    /** Two containers, fixed. Anything else is a caller bug. */
    private static final List<String> OUTPUT_FORMATS = List.of("mp3", "wav");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Drains ffmpeg/ffprobe output off the calling thread — see {@link #run}. */
    private static final java.util.concurrent.ExecutorService DRAIN =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /** ffprobe/ffmpeg is chatty on stderr even at error level; keep
     *  only the tail for exception messages. */
    private static final int STDERR_TAIL_LINES = 8;

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private final String ffmpegBin;
    private final String ffprobeBin;
    private final long timeoutSeconds;
    /** Sealed child environment — ffmpeg sees none of the brain's credentials. */
    private final ProcessLauncher launcher;

    private final ScratchArea scratchArea;

    public FfmpegAudioEditor(
            @Value("${vance.audio.ffmpeg-bin:ffmpeg}") String ffmpegBin,
            @Value("${vance.audio.ffprobe-bin:ffprobe}") String ffprobeBin,
            @Value("${vance.audio.edit-timeout-seconds:600}") long timeoutSeconds,
            ProcessLauncher launcher,
            ScratchArea scratchArea) {
        this.launcher = launcher;
        this.scratchArea = scratchArea;
        this.ffmpegBin = ffmpegBin;
        this.ffprobeBin = ffprobeBin;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * {@link ToolCapabilities#AUDIO_TOOLS} when both ffmpeg and ffprobe run here —
     * the cloud image ships without them, and then the audio edit tools stay out of
     * the inventory instead of failing on every call.
     */
    @Override
    public Set<String> capabilities() {
        boolean ffmpeg = ProgramProbe.runs(launcher, List.of(ffmpegBin, "-version"), PROBE_TIMEOUT);
        boolean ffprobe = ProgramProbe.runs(launcher, List.of(ffprobeBin, "-version"), PROBE_TIMEOUT);
        if (ffmpeg && ffprobe) {
            return Set.of(ToolCapabilities.AUDIO_TOOLS);
        }
        log.info(
                "Audio edit tools unavailable: ffmpeg {}, ffprobe {}",
                ffmpeg ? "runs" : "missing",
                ffprobe ? "runs" : "missing");
        return Set.of();
    }

    /** What ffprobe reports about one audio input. */
    public record ProbeData(
            @Nullable Double durationSeconds,
            @Nullable String format,
            @Nullable String codec,
            @Nullable Integer channels,
            @Nullable Integer sampleRate) {}

    // ─────────────────── Public ops ───────────────────

    /** Probe container/stream metadata of the given audio bytes. */
    public ProbeData probe(byte[] audio) {
        try (Scratch scratch = openScratch()) {
            Path in = write(scratch, "in.bin", audio);
            String json = run(
                    List.of(
                            ffprobeBin,
                            "-v",
                            "error",
                            "-show_entries",
                            "format=duration,format_name",
                            "-show_entries",
                            "stream=codec_type,codec_name,sample_rate,channels",
                            "-of",
                            "json",
                            in.toString()),
                    scratch);
            return parseProbeJson(json);
        }
    }

    /**
     * Cut {@code [startSeconds, endSeconds)} out of the input and
     * optionally fade the cut in/out. {@code endSeconds == null} ⇒ cut
     * to the end of the input. Seeks are decode-accurate (after
     * {@code -i}); the duration window uses {@code -t} so it is
     * unambiguous under the offset.
     */
    public byte[] trim(
            byte[] audio,
            double startSeconds,
            @Nullable Double endSeconds,
            double fadeInSeconds,
            double fadeOutSeconds,
            String outputFormat) {
        ensureOutputFormat(outputFormat);
        try (Scratch scratch = openScratch()) {
            Path in = write(scratch, "in.bin", audio);
            Path out = scratch.file("out." + outputFormat);
            List<String> args = new ArrayList<>(List.of("-i", in.toString()));
            if (startSeconds > 0) {
                args.addAll(List.of("-ss", num(startSeconds)));
            }
            if (endSeconds != null) {
                args.addAll(List.of("-t", num(endSeconds - startSeconds)));
            }
            String fades = fadeChain(fadeInSeconds, fadeOutSeconds, cutLength(audio, startSeconds, endSeconds));
            if (fades != null) {
                args.addAll(List.of("-af", fades));
            }
            args.addAll(codecArgs(outputFormat));
            args.add(out.toString());
            runFfmpeg(args, scratch);
            return readOut(out);
        }
    }

    /**
     * Mix {@code overlay} onto {@code base}. The mix length follows the
     * base ({@code duration=first}); the overlay is delayed to
     * {@code overlayAtSeconds}, gain-adjusted, and — when
     * {@code duckOverlay} — sidechain-compressed by the base so a music
     * bed ducks under narration.
     */
    public byte[] mix(
            byte[] base,
            byte[] overlay,
            double baseGain,
            double overlayGain,
            double overlayAtSeconds,
            boolean duckOverlay,
            double fadeOutSeconds,
            String outputFormat) {
        ensureOutputFormat(outputFormat);
        try (Scratch scratch = openScratch()) {
            Path inBase = write(scratch, "base.bin", base);
            Path inOverlay = write(scratch, "overlay.bin", overlay);
            Path out = scratch.file("out." + outputFormat);

            StringBuilder filter = new StringBuilder();
            filter.append("[0:a]volume=").append(num(baseGain)).append("[a0];");
            filter.append("[1:a]volume=").append(num(overlayGain));
            if (overlayAtSeconds > 0) {
                long delayMs = Math.round(overlayAtSeconds * 1000.0);
                filter.append(",adelay=").append(delayMs).append(":all=1");
            }
            filter.append("[a1];");
            if (duckOverlay) {
                filter.append("[a1][a0]sidechaincompress")
                        .append("=threshold=0.05:ratio=8:attack=20:release=400[ducked];");
                filter.append("[a0][ducked]amix=inputs=2:duration=first:dropout_transition=0[mix];");
            } else {
                filter.append("[a0][a1]amix=inputs=2:duration=first:dropout_transition=0[mix];");
            }
            if (fadeOutSeconds > 0) {
                Double length = baseDuration(base);
                if (length == null || fadeOutSeconds >= length) {
                    // The service layer rejects both cases with a
                    // PARAMETER_INVALID the caller can act on; reaching this
                    // branch means the layering was bypassed — fail loudly
                    // instead of fading the whole mix from zero.
                    throw new AudioManipulationException(
                            AudioManipulationException.Reason.PARAMETER_INVALID,
                            "fadeOutSeconds (" + fadeOutSeconds + ") cannot be placed: base duration is "
                                    + (length == null ? "not probeable" : length + "s"));
                }
                filter.append("[mix]afade=t=out:st=")
                        .append(num(length - fadeOutSeconds))
                        .append(":d=")
                        .append(num(fadeOutSeconds))
                        .append("[out]");
            } else {
                filter.append("[mix]anull[out]");
            }

            List<String> args = new ArrayList<>(List.of(
                    "-i",
                    inBase.toString(),
                    "-i",
                    inOverlay.toString(),
                    "-filter_complex",
                    filter.toString(),
                    "-map",
                    "[out]"));
            args.addAll(codecArgs(outputFormat));
            args.add(out.toString());
            runFfmpeg(args, scratch);
            return readOut(out);
        }
    }

    /**
     * Join {@code clips} in order. Inputs are resampled to a common
     * 48 kHz stereo float layout first so the concat filter never
     * rejects mismatched clips. {@code crossfadeSeconds > 0} chains
     * {@code acrossfade} instead of a hard cut.
     */
    public byte[] concat(List<byte[]> clips, double crossfadeSeconds, String outputFormat) {
        ensureOutputFormat(outputFormat);
        if (clips.size() < 2) {
            throw new AudioManipulationException(
                    AudioManipulationException.Reason.PARAMETER_INVALID, "concat needs at least 2 clips");
        }
        try (Scratch scratch = openScratch()) {
            List<String> args = new ArrayList<>();
            for (int i = 0; i < clips.size(); i++) {
                Path in = write(scratch, "in-" + i + ".bin", clips.get(i));
                args.addAll(List.of("-i", in.toString()));
            }
            StringBuilder filter = new StringBuilder();
            for (int i = 0; i < clips.size(); i++) {
                filter.append("[")
                        .append(i)
                        .append(":a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[c")
                        .append(i)
                        .append("];");
            }
            if (crossfadeSeconds > 0) {
                filter.append("[c0][c1]acrossfade=d=")
                        .append(num(crossfadeSeconds))
                        .append("[af1]");
                for (int i = 2; i < clips.size(); i++) {
                    filter.append(";[af")
                            .append(i - 1)
                            .append("][c")
                            .append(i)
                            .append("]acrossfade=d=")
                            .append(num(crossfadeSeconds))
                            .append("[af")
                            .append(i)
                            .append("]");
                }
                filter.append(";[af").append(clips.size() - 1).append("]anull[out]");
            } else {
                for (int i = 0; i < clips.size(); i++) {
                    filter.append("[c").append(i).append("]");
                }
                filter.append("concat=n=").append(clips.size()).append(":v=0:a=1[out]");
            }
            Path out = scratch.file("out." + outputFormat);
            args.addAll(List.of("-filter_complex", filter.toString(), "-map", "[out]"));
            args.addAll(codecArgs(outputFormat));
            args.add(out.toString());
            runFfmpeg(args, scratch);
            return readOut(out);
        }
    }

    /**
     * Re-encode into {@code outputFormat}, optionally resampling,
     * downmixing, setting the mp3 bitrate and loudness-normalising
     * (EBU R128 via {@code loudnorm}).
     */
    public byte[] convert(
            byte[] audio,
            @Nullable Integer sampleRate,
            @Nullable Integer channels,
            @Nullable Integer bitrateKbps,
            boolean normalize,
            String outputFormat) {
        ensureOutputFormat(outputFormat);
        try (Scratch scratch = openScratch()) {
            Path in = write(scratch, "in.bin", audio);
            Path out = scratch.file("out." + outputFormat);
            List<String> args = new ArrayList<>(List.of("-i", in.toString()));
            if (sampleRate != null) {
                args.addAll(List.of("-ar", Integer.toString(sampleRate)));
            }
            if (channels != null) {
                args.addAll(List.of("-ac", Integer.toString(channels)));
            }
            if (normalize) {
                args.addAll(List.of("-af", "loudnorm"));
            }
            args.addAll(codecArgs(outputFormat, bitrateKbps));
            args.add(out.toString());
            runFfmpeg(args, scratch);
            return readOut(out);
        }
    }

    // ─────────────────── ffmpeg plumbing ───────────────────

    private void runFfmpeg(List<String> args, Scratch scratch) {
        List<String> cmd = new ArrayList<>(List.of(ffmpegBin, "-hide_banner", "-loglevel", "error", "-y"));
        cmd.addAll(args);
        run(cmd, scratch);
    }

    /** Run a command as an explicit argument list; return combined
     *  output. Non-zero exit or timeout ⇒ PROCESSING_ERROR with the
     *  stderr tail. The child works in the call's scratch directory — an
     *  isolated child gets exactly that directory and no network. */
    private String run(List<String> cmd, Scratch scratch) {
        try {
            Process process = launcher.start(ProcessSpec.of(cmd, scratch.dir()).withWritable(List.of(scratch.dir())));
            // The drain runs on its own thread so the wall-clock timeout below
            // can fire: reading to EOF on the calling thread would block until
            // the process exits — and a hung ffmpeg never exits, so the
            // timeout would be dead code. The drain thread is short-lived (it
            // ends with the process) and carries no state; a shared
            // per-call-thread executor is enough.
            var output = DRAIN.submit(() -> {
                try (var in = process.getInputStream()) {
                    return in.readAllBytes();
                }
            });
            byte[] bytes;
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                output.cancel(false);
                process.destroyForcibly();
                throw processingError("ffmpeg timed out after " + timeoutSeconds + "s", null);
            }
            // The process exited, so EOF stands — the drain returns promptly.
            bytes = output.get();
            if (process.exitValue() != 0) {
                throw processingError(stderrTail(bytes), null);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw processingError("ffmpeg/ffprobe failed to run: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw processingError("ffmpeg/ffprobe interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            // The drain thread died (stream error while the process was
            // exiting) — the run itself failed either way.
            throw processingError("ffmpeg/ffprobe output could not be read: " + e.getCause(), e);
        }
    }

    private static String stderrTail(byte[] output) {
        String text = new String(output, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return "ffmpeg failed without diagnostics";
        }
        String[] lines = text.split("\n");
        int from = Math.max(0, lines.length - STDERR_TAIL_LINES);
        StringBuilder tail = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            if (i > from) {
                tail.append('\n');
            }
            tail.append(lines[i].trim());
        }
        return tail.toString();
    }

    private ProbeData parseProbeJson(String json) {
        JsonNode root = MAPPER.readTree(json);
        Double duration = null;
        String format = root.path("format").path("format_name").asText(null);
        String codec = null;
        Integer channels = null;
        Integer sampleRate = null;
        String durationText = root.path("format").path("duration").asText(null);
        if (durationText != null && !durationText.isBlank()) {
            try {
                duration = Double.parseDouble(durationText);
            } catch (NumberFormatException ignored) {
                // exotic container without a parseable duration — leave null
            }
        }
        for (JsonNode stream : root.path("streams")) {
            if (!"audio".equals(stream.path("codec_type").asText(null))) {
                continue;
            }
            codec = stream.path("codec_name").asText(null);
            channels = stream.path("channels").isMissingNode()
                    ? null
                    : stream.path("channels").asInt();
            String rate = stream.path("sample_rate").asText(null);
            if (rate != null && !rate.isBlank()) {
                try {
                    sampleRate = Integer.parseInt(rate);
                } catch (NumberFormatException ignored) {
                    // leave null
                }
            }
            break;
        }
        return new ProbeData(duration, format, codec, channels, sampleRate);
    }

    private static List<String> codecArgs(String outputFormat) {
        return codecArgs(outputFormat, null);
    }

    private static List<String> codecArgs(String outputFormat, @Nullable Integer bitrateKbps) {
        if ("wav".equals(outputFormat)) {
            return List.of("-c:a", "pcm_s16le");
        }
        return List.of("-c:a", "libmp3lame", "-b:a", (bitrateKbps != null ? bitrateKbps : 192) + "k");
    }

    /**
     * Build the fade chain for a cut. The fade-out start is relative to
     * the cut output timeline, so the cut length must be known —
     * probed lazily only when a fade-out is requested.
     */
    private @Nullable String fadeChain(double fadeIn, double fadeOut, @Nullable Double cutLength) {
        if (fadeIn <= 0 && fadeOut <= 0) {
            return null;
        }
        StringBuilder chain = new StringBuilder();
        if (fadeIn > 0) {
            chain.append("afade=t=in:st=0:d=").append(num(fadeIn));
        }
        if (fadeOut > 0) {
            if (chain.length() > 0) {
                chain.append(',');
            }
            if (cutLength == null) {
                // The service layer rejects this up front with a
                // PARAMETER_INVALID the caller can act on; failing loudly here
                // keeps the editor honest even when called through another path.
                throw new AudioManipulationException(
                        AudioManipulationException.Reason.PARAMETER_INVALID,
                        "fadeOutSeconds cannot be placed: the cut length is not probeable");
            }
            if (fadeOut >= cutLength) {
                throw new AudioManipulationException(
                        AudioManipulationException.Reason.PARAMETER_INVALID,
                        "fadeOutSeconds (" + fadeOut + ") must be smaller than the cut length (" + cutLength + ")");
            }
            chain.append("afade=t=out:st=")
                    .append(num(cutLength - fadeOut))
                    .append(":d=")
                    .append(num(fadeOut));
        }
        return chain.toString();
    }

    /** Cut length for fade-out placement — probed only when needed. */
    private @Nullable Double cutLength(byte[] audio, double startSeconds, @Nullable Double endSeconds) {
        if (endSeconds != null) {
            return endSeconds - startSeconds;
        }
        Double duration = probe(audio).durationSeconds();
        return duration == null ? null : Math.max(0.0, duration - startSeconds);
    }

    /** Base duration for mix fade-out placement — probed only when
     *  needed; {@code null} when the container reports none. */
    private @Nullable Double baseDuration(byte[] base) {
        return probe(base).durationSeconds();
    }

    private static void ensureOutputFormat(String outputFormat) {
        String normalized = outputFormat == null ? "" : outputFormat.toLowerCase(Locale.ROOT);
        if (!OUTPUT_FORMATS.contains(normalized)) {
            throw new AudioManipulationException(
                    AudioManipulationException.Reason.FORMAT_UNSUPPORTED,
                    "Output format '" + outputFormat + "' is not supported; use mp3 or wav");
        }
    }

    /** Fixed dot-decimal rendering so filter strings are
     *  locale-independent. */
    private static String num(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private Scratch openScratch() {
        try {
            return scratchArea.open("ffmpeg");
        } catch (IOException e) {
            throw processingError("failed to create scratch directory: " + e.getMessage(), e);
        }
    }

    private static Path write(Scratch scratch, String name, byte[] content) {
        try {
            return scratch.write(name, content);
        } catch (IOException e) {
            throw processingError("failed to write scratch file: " + e.getMessage(), e);
        }
    }

    private static byte[] readOut(Path out) {
        try {
            return Files.readAllBytes(out);
        } catch (IOException e) {
            throw processingError("failed to read ffmpeg output: " + e.getMessage(), e);
        }
    }

    private static AudioManipulationException processingError(String message, @Nullable Throwable cause) {
        return cause == null
                ? new AudioManipulationException(AudioManipulationException.Reason.PROCESSING_ERROR, message)
                : new AudioManipulationException(AudioManipulationException.Reason.PROCESSING_ERROR, message, cause);
    }
}
