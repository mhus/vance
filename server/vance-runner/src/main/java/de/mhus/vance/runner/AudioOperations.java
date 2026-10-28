package de.mhus.vance.runner;

import de.mhus.vance.exec.FfmpegEngine;
import de.mhus.vance.exec.MechException;
import de.mhus.vance.runner.RunnerOperation.Call;
import de.mhus.vance.runner.RunnerOperation.OperationException;
import de.mhus.vance.runner.RunnerOperation.OperationResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code audio.probe}, {@code audio.trim}, {@code audio.mix}, {@code audio.concat},
 * {@code audio.convert}: the ffmpeg work of the brain's audio tools, run here when the brain's
 * own pod has no ffmpeg ({@code planning/runner.md} §7, R-4). Stateless — a scratch directory
 * per call, nothing kept.
 *
 * <p>The inputs arrive as the request body, concatenated, with their sizes in
 * {@code inputSizes}; the edited audio leaves as the answer's body. The programs are
 * {@link FfmpegEngine}'s, the same the brain's pod would use. A failure carries the audio tools'
 * reason, lower-cased, as its code.
 */
final class AudioOperations {

    private AudioOperations() {}

    static List<RunnerOperation> all(RunnerWorkspace workspace) {
        return List.of(probe(workspace), trim(workspace), mix(workspace), concat(workspace), convert(workspace));
    }

    private static RunnerOperation probe(RunnerWorkspace workspace) {
        return operation(workspace, "audio.probe", (engine, args, inputs) -> {
            FfmpegEngine.ProbeData data = engine.probe(single(inputs));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("durationSeconds", data.durationSeconds());
            out.put("format", data.format());
            out.put("codec", data.codec());
            out.put("channels", data.channels());
            out.put("sampleRate", data.sampleRate());
            return OperationResult.of(out);
        });
    }

    private static RunnerOperation trim(RunnerWorkspace workspace) {
        return operation(
                workspace,
                "audio.trim",
                (engine, args, inputs) -> edited(engine.trim(
                        single(inputs),
                        args.decimal("startSeconds", 0),
                        args.decimalOrNull("endSeconds"),
                        args.decimal("fadeInSeconds", 0),
                        args.decimal("fadeOutSeconds", 0),
                        args.text("outputFormat"))));
    }

    private static RunnerOperation mix(RunnerWorkspace workspace) {
        return operation(workspace, "audio.mix", (engine, args, inputs) -> {
            if (inputs.size() != 2) {
                throw new OperationException(
                        "bad-args", "audio.mix takes two inputs (base, overlay), got " + inputs.size());
            }
            return edited(engine.mix(
                    inputs.get(0),
                    inputs.get(1),
                    args.decimal("baseGain", 1),
                    args.decimal("overlayGain", 1),
                    args.decimal("overlayAtSeconds", 0),
                    args.flag("duckOverlay"),
                    args.decimal("fadeOutSeconds", 0),
                    args.text("outputFormat")));
        });
    }

    private static RunnerOperation concat(RunnerWorkspace workspace) {
        return operation(
                workspace,
                "audio.concat",
                (engine, args, inputs) ->
                        edited(engine.concat(inputs, args.decimal("crossfadeSeconds", 0), args.text("outputFormat"))));
    }

    private static RunnerOperation convert(RunnerWorkspace workspace) {
        return operation(
                workspace,
                "audio.convert",
                (engine, args, inputs) -> edited(engine.convert(
                        single(inputs),
                        args.integerOrNull("sampleRate"),
                        args.integerOrNull("channels"),
                        args.integerOrNull("bitrateKbps"),
                        args.flag("normalize"),
                        args.text("outputFormat"))));
    }

    private static OperationResult edited(byte[] audio) {
        return OperationResult.of(Map.of("bytes", audio.length), new ByteArrayInputStream(audio));
    }

    private static byte[] single(List<byte[]> inputs) throws OperationException {
        if (inputs.size() != 1) {
            throw new OperationException("bad-args", "This operation takes one input, got " + inputs.size());
        }
        return inputs.getFirst();
    }

    /** One operation's work on the engine, the arguments and the split-up request body. */
    @FunctionalInterface
    private interface Work {
        OperationResult run(FfmpegEngine engine, OperationArgs args, List<byte[]> inputs) throws OperationException;
    }

    private static RunnerOperation operation(RunnerWorkspace workspace, String name, Work work) {
        return new RunnerOperation() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public OperationResult execute(Call call) throws OperationException {
                OperationArgs args = new OperationArgs(call.args());
                List<byte[]> inputs = split(call, args.integers("inputSizes"));
                try {
                    return work.run(workspace.ffmpeg(), args, inputs);
                } catch (MechException e) {
                    throw new OperationException(e.reason().name().toLowerCase(Locale.ROOT), e.getMessage(), e);
                }
            }
        };
    }

    /** The request body cut into the inputs {@code sizes} names; it must be exactly that long. */
    private static List<byte[]> split(Call call, List<Integer> sizes) throws OperationException {
        byte[] body;
        try {
            body = call.body().readAllBytes();
        } catch (IOException e) {
            throw new OperationException("corrupt-body", "The request body is corrupt: " + e.getMessage(), e);
        }
        long total = sizes.stream().mapToLong(Integer::longValue).sum();
        if (total != body.length) {
            throw new OperationException(
                    "bad-args", "'inputSizes' add up to " + total + " bytes but the body has " + body.length);
        }
        List<byte[]> inputs = new ArrayList<>();
        int offset = 0;
        for (int size : sizes) {
            byte[] input = new byte[size];
            System.arraycopy(body, offset, input, 0, size);
            inputs.add(input);
            offset += size;
        }
        return inputs;
    }
}
