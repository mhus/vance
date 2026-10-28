package de.mhus.vance.brain.audio;

import de.mhus.vance.brain.tools.runner.RunnerRefusedException;
import de.mhus.vance.brain.tools.runner.RunnerWork;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Audio editing on the project's runner: the {@link AudioEngine} for a pod without ffmpeg.
 * One instance serves one project (the runner is addressed per project); the program runs on
 * the runner with the same {@code FfmpegEngine} the pod would use.
 *
 * <p>The inputs travel as the request body, concatenated, with their sizes in the arguments;
 * the result comes back as the answer's body. Nothing is stored on the runner.
 */
final class RunnerAudioEngine implements AudioEngine {

    private final RunnerWork runner;
    private final ToolInvocationContext scope;

    RunnerAudioEngine(RunnerWork runner, String tenantId, @Nullable String projectId) {
        this.runner = runner;
        this.scope = new ToolInvocationContext(tenantId, projectId, null, null, null);
    }

    @Override
    public ProbeData probe(byte[] audio) {
        Map<String, Object> result =
                call("audio.probe", Map.of(), List.of(audio)).result();
        return new ProbeData(
                number(result.get("durationSeconds")),
                text(result.get("format")),
                text(result.get("codec")),
                number(result.get("channels")) == null
                        ? null
                        : number(result.get("channels")).intValue(),
                number(result.get("sampleRate")) == null
                        ? null
                        : number(result.get("sampleRate")).intValue());
    }

    @Override
    public byte[] trim(
            byte[] audio,
            double startSeconds,
            @Nullable Double endSeconds,
            double fadeInSeconds,
            double fadeOutSeconds,
            String outputFormat) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("startSeconds", startSeconds);
        args.put("endSeconds", endSeconds);
        args.put("fadeInSeconds", fadeInSeconds);
        args.put("fadeOutSeconds", fadeOutSeconds);
        args.put("outputFormat", outputFormat);
        return call("audio.trim", args, List.of(audio)).body();
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
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("baseGain", baseGain);
        args.put("overlayGain", overlayGain);
        args.put("overlayAtSeconds", overlayAtSeconds);
        args.put("duckOverlay", duckOverlay);
        args.put("fadeOutSeconds", fadeOutSeconds);
        args.put("outputFormat", outputFormat);
        return call("audio.mix", args, List.of(base, overlay)).body();
    }

    @Override
    public byte[] concat(List<byte[]> clips, double crossfadeSeconds, String outputFormat) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("crossfadeSeconds", crossfadeSeconds);
        args.put("outputFormat", outputFormat);
        return call("audio.concat", args, clips).body();
    }

    @Override
    public byte[] convert(
            byte[] audio,
            @Nullable Integer sampleRate,
            @Nullable Integer channels,
            @Nullable Integer bitrateKbps,
            boolean normalize,
            String outputFormat) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sampleRate", sampleRate);
        args.put("channels", channels);
        args.put("bitrateKbps", bitrateKbps);
        args.put("normalize", normalize);
        args.put("outputFormat", outputFormat);
        return call("audio.convert", args, List.of(audio)).body();
    }

    private RunnerWork.Binary call(String op, Map<String, Object> params, List<byte[]> inputs) {
        Map<String, Object> args = new LinkedHashMap<>(params);
        List<Integer> sizes = new ArrayList<>();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] input : inputs) {
            sizes.add(input.length);
            body.writeBytes(input);
        }
        args.put("inputSizes", sizes);
        try {
            return runner.callBinary(scope, op, args, body.toByteArray());
        } catch (RunnerRefusedException e) {
            throw new AudioManipulationException(reason(e.code()), e.getMessage(), e);
        } catch (ToolException e) {
            // Not reachable, a bad registration, a refused token: the edit itself did not fail,
            // the way to the program did — retryable once the runner is back.
            throw new AudioManipulationException(AudioManipulationException.Reason.PROCESSING_ERROR, e.getMessage(), e);
        }
    }

    /** The runner's operation codes are the audio tools' reasons, lower-cased. */
    private static AudioManipulationException.Reason reason(String code) {
        try {
            return AudioManipulationException.Reason.valueOf(code.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AudioManipulationException.Reason.PROCESSING_ERROR;
        }
    }

    private static @Nullable Double number(@Nullable Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static @Nullable String text(@Nullable Object value) {
        return value instanceof String s ? s : null;
    }
}
