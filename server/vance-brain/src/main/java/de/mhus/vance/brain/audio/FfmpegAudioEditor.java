package de.mhus.vance.brain.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
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
 * shell string), a wall-clock timeout and temp files that are cleaned
 * up in a {@code finally} block. ffmpeg writes its diagnostics to
 * stderr only on failure ({@code -loglevel error}); the last lines of
 * that output become the {@code PROCESSING_ERROR} message.
 *
 * <p>Output format is always one of {@code mp3} (libmp3lame,
 * 192 kbit/s) or {@code wav} (pcm_s16le) — the two containers every
 * consumer of these documents can play.
 */
@Component
public class FfmpegAudioEditor {

    /** Two containers, fixed. Anything else is a caller bug. */
    private static final List<String> OUTPUT_FORMATS = List.of("mp3", "wav");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** ffprobe/ffmpeg is chatty on stderr even at error level; keep
     *  only the tail for exception messages. */
    private static final int STDERR_TAIL_LINES = 8;

    private final String ffmpegBin;
    private final String ffprobeBin;
    private final long timeoutSeconds;

    public FfmpegAudioEditor(
            @Value("${vance.audio.ffmpeg-bin:ffmpeg}") String ffmpegBin,
            @Value("${vance.audio.ffprobe-bin:ffprobe}") String ffprobeBin,
            @Value("${vance.audio.edit-timeout-seconds:600}") long timeoutSeconds) {
        this.ffmpegBin = ffmpegBin;
        this.ffprobeBin = ffprobeBin;
        this.timeoutSeconds = timeoutSeconds;
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
        Path in = null;
        try {
            in = writeTemp(audio, ".bin");
            String json = run(List.of(
                    ffprobeBin,
                    "-v",
                    "error",
                    "-show_entries",
                    "format=duration,format_name",
                    "-show_entries",
                    "stream=codec_type,codec_name,sample_rate,channels",
                    "-of",
                    "json",
                    in.toString()));
            return parseProbeJson(json);
        } finally {
            deleteQuietly(in);
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
        Path in = null;
        Path out = null;
        try {
            in = writeTemp(audio, ".bin");
            out = writeTemp(new byte[0], "." + outputFormat);
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
            runFfmpeg(args);
            return readOut(out);
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
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
        Path inBase = null;
        Path inOverlay = null;
        Path out = null;
        try {
            inBase = writeTemp(base, ".bin");
            inOverlay = writeTemp(overlay, ".bin");
            out = writeTemp(new byte[0], "." + outputFormat);

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
                double length = baseDuration(base);
                filter.append("[mix]afade=t=out:st=")
                        .append(num(Math.max(0.0, length - fadeOutSeconds)))
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
            runFfmpeg(args);
            return readOut(out);
        } finally {
            deleteQuietly(inBase);
            deleteQuietly(inOverlay);
            deleteQuietly(out);
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
        List<Path> ins = new ArrayList<>();
        Path out = null;
        try {
            List<String> args = new ArrayList<>();
            for (byte[] clip : clips) {
                Path in = writeTemp(clip, ".bin");
                ins.add(in);
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
            out = writeTemp(new byte[0], "." + outputFormat);
            args.addAll(List.of("-filter_complex", filter.toString(), "-map", "[out]"));
            args.addAll(codecArgs(outputFormat));
            args.add(out.toString());
            runFfmpeg(args);
            return readOut(out);
        } finally {
            for (Path in : ins) {
                deleteQuietly(in);
            }
            deleteQuietly(out);
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
        Path in = null;
        Path out = null;
        try {
            in = writeTemp(audio, ".bin");
            out = writeTemp(new byte[0], "." + outputFormat);
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
            runFfmpeg(args);
            return readOut(out);
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    // ─────────────────── ffmpeg plumbing ───────────────────

    private void runFfmpeg(List<String> args) {
        List<String> cmd = new ArrayList<>(List.of(ffmpegBin, "-hide_banner", "-loglevel", "error", "-y"));
        cmd.addAll(args);
        run(cmd);
    }

    /** Run a command as an explicit argument list; return combined
     *  output. Non-zero exit or timeout ⇒ PROCESSING_ERROR with the
     *  stderr tail. */
    private String run(List<String> cmd) {
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] output;
            try (var in = process.getInputStream()) {
                output = in.readAllBytes();
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw processingError("ffmpeg timed out after " + timeoutSeconds + "s", null);
            }
            if (process.exitValue() != 0) {
                throw processingError(stderrTail(output), null);
            }
            return new String(output, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw processingError("ffmpeg/ffprobe failed to run: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw processingError("ffmpeg/ffprobe interrupted", e);
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
            double length = cutLength != null ? cutLength : 0.0;
            chain.append("afade=t=out:st=")
                    .append(num(Math.max(0.0, length - fadeOut)))
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

    private Double baseDuration(byte[] base) {
        Double duration = probe(base).durationSeconds();
        return duration == null ? 0.0 : duration;
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

    private static Path writeTemp(byte[] content, String suffix) {
        try {
            Path file = Files.createTempFile("vance-audio-", suffix);
            Files.write(file, content);
            return file;
        } catch (IOException e) {
            throw processingError("failed to create temp file: " + e.getMessage(), e);
        }
    }

    private static byte[] readOut(Path out) {
        try {
            return Files.readAllBytes(out);
        } catch (IOException e) {
            throw processingError("failed to read ffmpeg output: " + e.getMessage(), e);
        }
    }

    private static void deleteQuietly(@Nullable Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // temp file cleanup is best-effort
        }
    }

    private static AudioManipulationException processingError(String message, @Nullable Throwable cause) {
        return cause == null
                ? new AudioManipulationException(AudioManipulationException.Reason.PROCESSING_ERROR, message)
                : new AudioManipulationException(AudioManipulationException.Reason.PROCESSING_ERROR, message, cause);
    }
}
