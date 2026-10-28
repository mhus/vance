package de.mhus.vance.runner;

import de.mhus.vance.exec.MechException;
import de.mhus.vance.exec.ScratchArea;
import de.mhus.vance.exec.ScratchArea.Scratch;
import de.mhus.vance.exec.WhisperEngine;
import de.mhus.vance.exec.YtDlpEngine;
import de.mhus.vance.runner.RunnerOperation.Call;
import de.mhus.vance.runner.RunnerOperation.OperationException;
import de.mhus.vance.runner.RunnerOperation.OperationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code video.transcript}: the audio of a YouTube video, downloaded and transcribed in one
 * call, for the brain whose own pod has neither yt-dlp nor Whisper ({@code planning/runner.md}
 * §7, R-4). The audio never leaves the runner — a scratch directory holds it for the call —
 * only the transcript comes back.
 *
 * <p>Arguments: {@code videoId} (an 11-character YouTube id), optional {@code model} and
 * {@code language}. The programs are {@link YtDlpEngine} and {@link WhisperEngine}, the same
 * code the brain's pod runs.
 */
final class VideoOperations {

    /** A YouTube video id: 11 characters. The URL yt-dlp gets is built from it, so nothing else passes. */
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private VideoOperations() {}

    static List<RunnerOperation> all(YtDlpEngine downloader, WhisperEngine transcriber, ScratchArea scratch) {
        return List.of(transcript(downloader, transcriber, scratch));
    }

    private static RunnerOperation transcript(
            YtDlpEngine downloader, WhisperEngine transcriber, ScratchArea scratchArea) {
        return new RunnerOperation() {
            @Override
            public String name() {
                return "video.transcript";
            }

            @Override
            public OperationResult execute(Call call) throws OperationException {
                OperationArgs args = new OperationArgs(call.args());
                String videoId = args.text("videoId");
                if (!VIDEO_ID.matcher(videoId).matches()) {
                    throw new OperationException("bad-args", "'videoId' must be an 11-character YouTube video id");
                }
                try (Scratch scratch = scratchArea.open("video")) {
                    Path audio = downloader.download(videoId, scratch.dir());
                    long audioBytes = Files.size(audio);
                    WhisperEngine.Result result = transcriber.transcribe(
                            audio, args.optionalText("model"), args.optionalText("language"), null);
                    return OperationResult.of(describe(result, audioBytes));
                } catch (MechException e) {
                    throw new OperationException(e.reason().name().toLowerCase(Locale.ROOT), e.getMessage(), e);
                } catch (IOException e) {
                    throw new OperationException(
                            "processing_error", "Could not prepare the audio: " + e.getMessage(), e);
                }
            }
        };
    }

    private static Map<String, Object> describe(WhisperEngine.Result result, long audioBytes) {
        List<Map<String, Object>> segments = new ArrayList<>();
        for (WhisperEngine.Fragment fragment : result.segments()) {
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("start", fragment.start());
            segment.put("end", fragment.end());
            segment.put("text", fragment.text());
            segments.add(segment);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("language", result.language());
        out.put("languageProbability", result.languageProbability());
        out.put("durationSec", result.durationSec());
        out.put("elapsedSec", result.elapsedSec());
        out.put("modelName", result.modelName());
        out.put("audioBytes", audioBytes);
        out.put("segments", segments);
        return out;
    }
}
