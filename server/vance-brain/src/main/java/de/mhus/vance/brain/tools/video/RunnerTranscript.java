package de.mhus.vance.brain.tools.video;

import de.mhus.vance.brain.tools.runner.RunnerWork;
import de.mhus.vance.exec.WhisperEngine;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Asks the project's runner for a video's transcript: {@code video.transcript} downloads the
 * audio and transcribes it there, and only the segments come back. The answer is turned into the
 * same {@link WhisperEngine.Result} the pod's own pipeline produces, so everything after it —
 * formatting, truncation, the tool result — is one path for both.
 */
final class RunnerTranscript {

    private RunnerTranscript() {}

    static WhisperEngine.Result fetch(
            RunnerWork runner,
            ToolInvocationContext ctx,
            String videoId,
            String model,
            @Nullable String language,
            Duration timeout) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("videoId", videoId);
        args.put("model", model);
        if (language != null) {
            args.put("language", language);
        }
        Map<String, Object> answer = runner.call(ctx, "video.transcript", args, new byte[0], null, timeout);
        List<WhisperEngine.Fragment> segments = new ArrayList<>();
        if (answer.get("segments") instanceof List<?> raw) {
            for (Object entry : raw) {
                if (entry instanceof Map<?, ?> m) {
                    segments.add(new WhisperEngine.Fragment(
                            number(m.get("start")), number(m.get("end")), String.valueOf(m.get("text"))));
                }
            }
        }
        return new WhisperEngine.Result(
                String.valueOf(answer.getOrDefault("language", "")),
                number(answer.get("languageProbability")),
                number(answer.get("durationSec")),
                number(answer.get("elapsedSec")),
                String.valueOf(answer.getOrDefault("modelName", "")),
                segments);
    }

    private static double number(@Nullable Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }
}
